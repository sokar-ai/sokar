package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for removing what Sokar built for a project.
 * <p>
 * This is the only thing here that destroys, so what is checked hardest is what it refuses to do
 * and what it leaves alone. An unreviewed push exists only in the mirror; a project file belongs
 * to the operator; and a preview that touched anything would make the confirmation a lie.
 */
class ProjectDeletionTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private SokarContext context(Path dir) {
        root = dir;
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A project with a mirror, a build directory, a registry entry and an upstream record. */
    private Path project(String name) throws IOException {
        final Path mirror = root.resolve("data/sokar/mirrors").resolve(name + ".git");
        Files.createDirectories(mirror.resolve("objects"));
        Files.createDirectories(root.resolve("data/sokar/build").resolve(name));
        Files.createDirectories(root.resolve("data/sokar/projects"));
        final Path file = root.resolve(name + "-project.yml");
        Files.writeString(file, "project:\n  name: \"" + name + "\"\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/sokar/projects").resolve(name),
                file + "\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("data/sokar/upstream"));
        Files.writeString(root.resolve("data/sokar/upstream").resolve(name),
                "MEASURED\t0\t\t\n", StandardCharsets.UTF_8);
        return file;
    }

    private void task(String container, String project, String state) throws IOException {
        runner.answering("ps", container + "\t" + state + "\n");
        final Path directory = root.resolve("run/sokar").resolve(container);
        Files.createDirectories(directory);
        new Sidecar(Sidecar.VERSION, project, "guarded", directory.resolve("r.nft").toString(),
                directory.resolve("dns.conf").toString(), "/usr/bin/sokar", directory.toString())
                .writeTo(directory.resolve("sidecar.json"));
    }

    @Test
    void refusesWhileWorkIsWaitingAtTheGateAndRemovesNothing(@TempDir Path dir)
            throws IOException {

        // The refusal that justifies the whole confirmation. A branch that reached the mirror and
        // was never approved exists NOWHERE else - not in the operator's repository, not in a
        // workspace. Losing it to a tidy-up is the worst thing this could do.
        final SokarContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "refs/sokar/incoming/shell\n");

        final ProjectDeletion.Result result = new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.HOLDS_WORK);
        assertThat(result.unreviewed()).containsExactly("shell");
        assertThat(Files.isDirectory(dir.resolve("data/sokar/mirrors/uc.git")))
                .as("the mirror is untouched").isTrue();
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rmi"));
    }

    @Test
    void refusesWhileATaskIsUp(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        project("uc");
        task("sokar-uc-shell-1", "uc", "Up 4 minutes");
        runner.answering("for-each-ref", "");

        final ProjectDeletion.Result result = new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.TASKS_RUNNING);
        assertThat(result.running()).containsExactly("sokar-uc-shell-1");
        assertThat(Files.isDirectory(dir.resolve("data/sokar/mirrors/uc.git")))
                .as("nothing was removed").isTrue();
    }

    @Test
    void stopsWhenATaskWillNotGiveUpWhatItHolds(@TempDir Path dir) throws IOException {

        // The answer from removing each task was discarded. A task refusing because it holds work
        // the gate never saw was ignored, and the mirror - the only place unreviewed pushes exist
        // - was deleted a moment later. Nothing reported it, because nothing looked.
        final SokarContext context = context(dir);
        project("uc");
        task("sokar-uc-shell-1", "uc", "Exited (0) 2 minutes ago");
        runner.answering("for-each-ref", "");
        // Stopped, and nothing recorded what it holds: 'task remove' answers NOTHING_KNOWS, which
        // is a refusal and not a removal.

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.HOLDS_WORK);
        assertThat(result.detail()).contains("sokar-uc-shell-1").contains("was not removed");
        assertThat(Files.isDirectory(dir.resolve("data/sokar/mirrors/uc.git")))
                .as("the mirror is where unreviewed pushes live, and nothing else has them")
                .isTrue();
    }

    @Test
    void forceProceedsAndStillNamesWhatItDestroyed(@TempDir Path dir) throws IOException {

        // Saying it twice means the same here as for 'task stop --force'. What force must NOT do
        // is hide what it took: the unreviewed refs stay in the answer, because they are what it
        // destroyed.
        final SokarContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "refs/sokar/incoming/shell\n");

        final ProjectDeletion.Result result = new ProjectDeletion(context).delete("uc", false, true);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.DELETED);
        assertThat(result.unreviewed()).as("what force destroyed, still named")
                .containsExactly("shell");
        assertThat(Files.exists(dir.resolve("data/sokar/mirrors/uc.git"))).isFalse();
    }

    @Test
    void neverTouchesTheProjectFile(@TempDir Path dir) throws IOException {

        // It is the operator's, in their own directory. A deletion that took it would be claiming
        // ownership of something Sokar merely reads - and 'keeps' says so, so a confirmation can
        // too without the interface having to know which things are ours.
        final SokarContext context = context(dir);
        final Path file = project("uc");
        runner.answering("for-each-ref", "");

        final ProjectDeletion.Result result = new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.DELETED);
        assertThat(Files.isRegularFile(file)).as("the operator's file survives").isTrue();
        assertThat(result.keeps()).anyMatch(kept -> kept.equals(file.toString()));
    }

    @Test
    void aPreviewRemovesNothingAndStillNamesEverything(@TempDir Path dir) throws IOException {

        // A confirmation is built from this. If the preview changed anything, the dialog would be
        // reporting a past event as a future one.
        final SokarContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");

        final ProjectDeletion.Result result = new ProjectDeletion(context).delete("uc", true, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.PREVIEWED);
        assertThat(result.removes()).extracting(ProjectDeletion.Removal::kind)
                .contains("MIRROR", "BUILD", "REGISTRY", "UPSTREAM_RECORD");
        assertThat(Files.isDirectory(dir.resolve("data/sokar/mirrors/uc.git"))).isTrue();
        assertThat(Files.isRegularFile(dir.resolve("data/sokar/projects/uc"))).isTrue();
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rmi"));
    }

    @Test
    void aProjectNothingKnowsIsAnOutcomeRatherThanAnError(@TempDir Path dir) {

        assertThat(new ProjectDeletion(context(dir)).delete("never-existed", false, false).outcome())
                .isEqualTo(ProjectDeletion.Outcome.NO_SUCH_PROJECT);
    }

    @Test
    void removesEveryThingSokarBuiltForIt(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");

        new ProjectDeletion(context).delete("uc", false, false);

        assertThat(Files.exists(dir.resolve("data/sokar/mirrors/uc.git"))).isFalse();
        assertThat(Files.exists(dir.resolve("data/sokar/build/uc"))).isFalse();
        assertThat(Files.exists(dir.resolve("data/sokar/projects/uc"))).isFalse();
        assertThat(Files.exists(dir.resolve("data/sokar/upstream/uc"))).isFalse();
        assertThat(runner.lines()).anyMatch(line -> line.contains("rmi") && line.contains("sokar/uc"));
    }
}
