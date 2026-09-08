package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for creating a project file.
 * <p>
 * Two things carry the weight. What is written must be readable by the project reader - a file
 * this wrote and that refused would be the worst outcome, because it looks like corruption. And an
 * answer this machine cannot accept has to be refused here rather than at the first task start,
 * which is minutes later and somewhere else.
 */
class ProjectCreationTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    private ProjectCreation.Result create(Path dir, String name, String securityClass,
            List<String> sets, boolean dryRun) {
        return ProjectCreation.create(context(dir), dir.resolve("project.yml"), name,
                securityClass, "ubuntu:24.04", null, sets, dryRun);
    }

    @Test
    void whatIsWrittenIsWhatTheProjectReaderAccepts(@TempDir Path dir) {

        // The whole point of validating here. A file this wrote and the reader refused would read
        // as corruption, and somebody would go looking for a damaged disk.
        final ProjectCreation.Result result = create(dir, "demo", "guarded", List.of(), false);

        assertThat(result.outcome()).isEqualTo(ProjectCreation.Outcome.CREATED);
        assertThat(ProjectReader.read(dir.resolve("project.yml")).name()).isEqualTo("demo");
    }

    @Test
    void aNameThatCannotBecomeAnImageTagIsRefusedHereAndNotAtTheFirstTask(@TempDir Path dir) {

        // A name becomes an image tag, a container name and an nftables set name. Finding that out
        // minutes later, from a build that fails, is finding it out in the wrong place.
        final ProjectCreation.Result result = create(dir, "My Project", "guarded", List.of(),
                false);

        assertThat(result.outcome()).isEqualTo(ProjectCreation.Outcome.INVALID);
        assertThat(result.problems()).anySatisfy(problem -> {
            assertThat(problem.field()).isEqualTo("name");
            assertThat(problem.fatal()).isTrue();
        });
        assertThat(dir.resolve("project.yml")).doesNotExist();
    }

    @Test
    void aMisspeltSecurityClassIsRefused(@TempDir Path dir) {

        assertThat(create(dir, "demo", "gaurded", List.of(), false).outcome())
                .isEqualTo(ProjectCreation.Outcome.INVALID);
        assertThat(dir.resolve("project.yml")).doesNotExist();
    }

    @Test
    void anEgressSetThisMachineDoesNotHaveIsRefusedAndTheAvailableOnesNamed(@TempDir Path dir)
            throws Exception {

        // Only this machine knows which sets it has, which is why a client cannot check it. An
        // unknown one fails at task start as a name resolving to nothing, which reads as a broken
        // build rather than as a typo in a file.
        final ProjectCreation.Result result = create(dir, "demo", "guarded",
                List.of("no-such-set"), false);

        assertThat(result.outcome()).isEqualTo(ProjectCreation.Outcome.INVALID);
        assertThat(result.problems()).anySatisfy(problem -> {
            assertThat(problem.field()).isEqualTo("sets");
            assertThat(problem.detail()).contains("available:");
        });
    }

    @Test
    void anOnlineProjectWithoutAnUpstreamIsRefused(@TempDir Path dir) {

        // An online project pushes to its upstream itself. Without one it is a project that can
        // never send anything anywhere, which nobody means to create.
        final ProjectCreation.Result result = ProjectCreation.create(context(dir),
                dir.resolve("project.yml"), "demo", "online", "ubuntu:24.04", null, List.of(),
                false);

        assertThat(result.outcome()).isEqualTo(ProjectCreation.Outcome.INVALID);
        assertThat(result.problems()).anySatisfy(problem ->
                assertThat(problem.field()).isEqualTo("upstream"));
    }

    @Test
    void aBaseImageThatIsNotHereYetIsWorthSayingAndNotWorthRefusing(@TempDir Path dir) {

        // Pulling it reaches the network, takes minutes and can hang, and none of that belongs in
        // answering a question. So it is reported and the project is still created.
        runner.failing("exists", 1, "");

        final ProjectCreation.Result result = create(dir, "demo", "guarded", List.of(), false);

        assertThat(result.outcome()).isEqualTo(ProjectCreation.Outcome.CREATED);
        assertThat(result.problems()).anySatisfy(problem -> {
            assertThat(problem.field()).isEqualTo("baseImage");
            assertThat(problem.fatal()).as("not being able to start is different from not being"
                    + " able to create").isFalse();
        });
    }

    @Test
    void anExistingFileIsRefusedRatherThanOverwritten(@TempDir Path dir) throws Exception {

        // It may be somebody's whole configuration, and this is the one operation that would
        // replace it with nothing to restore from.
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, "theirs, not ours\n");

        final ProjectCreation.Result result = create(dir, "demo", "guarded", List.of(), false);

        assertThat(result.outcome()).isEqualTo(ProjectCreation.Outcome.ALREADY_EXISTS);
        assertThat(Files.readString(file)).isEqualTo("theirs, not ours\n");
    }

    @Test
    void aPreviewWritesNothingAndStillShowsTheFile(@TempDir Path dir) {

        // Reviewing what would be written is the point of asking, so a preview that answered
        // nothing would leave a client rendering a blank where the review belongs.
        final ProjectCreation.Result result = create(dir, "demo", "guarded", List.of(), true);

        assertThat(result.outcome()).isEqualTo(ProjectCreation.Outcome.PREVIEWED);
        assertThat(result.content()).contains("name: \"demo\"");
        assertThat(dir.resolve("project.yml")).doesNotExist();
    }

    @Test
    void aRefusalStillShowsWhatWasRejected(@TempDir Path dir) {

        // Seeing the file that was refused is most of understanding why it was refused.
        assertThat(create(dir, "My Project", "guarded", List.of(), false).content())
                .isNotBlank().contains("security_class");
    }

    @Test
    void nothingHalfWrittenIsLeftBehind(@TempDir Path dir) throws Exception {

        // Written to a temporary name and moved into place, so a caller that stops mid-write
        // leaves either a whole file or none - never one the reader refuses as corrupt.
        create(dir, "demo", "guarded", List.of(), false);

        try (var entries = Files.list(dir)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .allSatisfy(entry -> assertThat(entry).doesNotContain(".tmp"));
        }
    }

    @Test
    void anOfflineProjectNeedsNoUpstream(@TempDir Path dir) {

        assertThat(create(dir, "demo", "offline", List.of(), false).outcome())
                .isEqualTo(ProjectCreation.Outcome.CREATED);
    }
}
