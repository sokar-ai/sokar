package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for the verb that ends a project.
 * <p>
 * <strong>What is measured here is the project nobody follows.</strong> A project made before
 * following existed - a task was started with a file, which wrote a mirror and a registry entry -
 * is in no follow record, and this command used to refuse it outright. The project stayed in the
 * listing with its mirror and its image and no command ended it; two of them were cleared by hand
 * on a test machine, which is not something an interface can do. What is at risk is the same
 * either way, so the checks have to be the same either way.
 */
class ProjectUnfollowCommandTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private SokarContext context(final Path dir) {
        root = dir;
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A project the old way: a mirror, a build directory and a registry entry. Nothing follows it. */
    private void unfollowedProject(final String name) throws IOException {
        Files.createDirectories(root.resolve("data/sokar/mirrors").resolve(name + ".git")
                .resolve("objects"));
        Files.createDirectories(root.resolve("data/sokar/build").resolve(name));
        Files.createDirectories(root.resolve("data/sokar/projects"));
        final Path file = root.resolve(name + "-project.yml");
        Files.writeString(file, "project:\n  name: \"" + name + "\"\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/sokar/projects").resolve(name), file + "\n",
                StandardCharsets.UTF_8);
    }

    private record Run(int code, String out, String err) { }

    private Run run(final SokarContext context, final String... arguments) {
        final ProjectUnfollowCommand command = new ProjectUnfollowCommand();
        command.setContext(context);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int code = new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(arguments);
        return new Run(code, out.toString(), err.toString());
    }

    @Test
    void previews_a_project_nobody_follows_rather_than_refusing_to_look(@TempDir final Path dir)
            throws IOException {

        final SokarContext context = context(dir);
        unfollowedProject("objects4j");

        final Run previewed = run(context, "objects4j", "--dry-run");

        assertThat(previewed.code()).isZero();
        assertThat(previewed.out()).contains("would remove objects4j")
                .contains("does not follow")
                // What goes, by name. A preview that says only "yes, something" is not a preview.
                .contains("mirrors")
                .contains("objects4j-project.yml");
        // And what stays, as plainly as what goes: the project file is the operator's.
        assertThat(previewed.out()).contains("kept:");
        // A preview removes nothing. The whole worth of the confirmation rests on this.
        assertThat(dir.resolve("data/sokar/mirrors/objects4j.git")).exists();
    }

    @Test
    void clears_a_project_nobody_follows(@TempDir final Path dir) throws IOException {

        final SokarContext context = context(dir);
        unfollowedProject("utils4j");

        final Run cleared = run(context, "utils4j");

        assertThat(cleared.code()).isZero();
        assertThat(cleared.out()).contains("was not followed here");
        assertThat(dir.resolve("data/sokar/mirrors/utils4j.git")).doesNotExist();
        assertThat(dir.resolve("data/sokar/projects/utils4j")).doesNotExist();
        assertThat(dir.resolve("data/sokar/build/utils4j")).doesNotExist();
        // The operator's own file is not Sokar's to remove, whichever verb was typed.
        assertThat(dir.resolve("utils4j-project.yml")).exists();
    }

    @Test
    void a_name_this_machine_does_not_list_is_still_its_own_answer(@TempDir final Path dir) {

        // The distinction is the value of the error: "it is here and you do not follow it" is a
        // thing to act on, "there is no such project" is a typo.
        final Run missing = run(context(dir), "nowhere", "--dry-run");

        assertThat(missing.code()).isEqualTo(70);
        assertThat(missing.err()).contains("no project called 'nowhere' here");
    }

    @Test
    void refuses_a_project_nobody_follows_that_holds_unreviewed_work(@TempDir final Path dir)
            throws IOException {

        // The point of the whole requirement. These are the pushes that exist in the mirror and
        // nowhere else, and they do not become less valuable because the project predates
        // following. Without this, clearing such a project from an interface would be blind.
        final SokarContext context = context(dir);
        unfollowedProject("objects4j");
        final Path mirror = dir.resolve("data/sokar/mirrors/objects4j.git");
        runner.answering("for-each-ref", "refs/sokar/incoming/shell\n");

        final Run refused = run(context, "objects4j");

        assertThat(refused.code()).isEqualTo(70);
        assertThat(refused.err()).contains("holds work nobody has reviewed");
        assertThat(mirror).exists();
    }

    @Test
    void takes_the_verified_clone_with_the_rest(@TempDir final Path dir) throws IOException {

        // It did not, over the socket: the terminal removed the clone by hand AFTER the deletion
        // and the daemon never did, so a project unfollowed from an interface left its verified
        // clone on the machine - while the contract said the clone goes. Found through the interface.
        // Removed through ProjectDeletion now, which is the one thing both ways call, and it is
        // named in the preview for the same reason.
        final SokarContext context = context(dir);
        unfollowedProject("uc");
        final Path clone = context.paths().projects().followedClone("uc");
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("project.yml"), "project:\n  name: \"uc\"\n",
                StandardCharsets.UTF_8);

        final Run previewed = run(context, "uc", "--dry-run");
        assertThat(previewed.out()).contains(clone.toString());
        // Readable, not a record's toString: this is the screen somebody decides on.
        assertThat(previewed.out()).contains("followed clone").doesNotContain("Removal[");
        // And what is inside the clone is NOT listed as kept, because the clone goes. Saying
        // both about one path in one preview is worse than saying neither.
        assertThat(previewed.out().lines().filter(line -> line.contains("kept:"))
                .anyMatch(line -> line.contains(clone.toString()))).isFalse();
        assertThat(clone).exists();

        run(context, "uc");

        assertThat(clone).doesNotExist();
    }
}
