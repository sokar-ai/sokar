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
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * What the delete command SAYS, which is a separate question from what it does.
 * <p>
 * The domain side is covered by {@link ProjectDeletionTest}; this is about the report, because
 * that is where a run that removed nothing claimed five removals.
 */
class ProjectDeleteCommandTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

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

    private void project(String name) throws IOException {
        Files.createDirectories(root.resolve("data/sokar/mirrors").resolve(name + ".git")
                .resolve("objects"));
        Files.createDirectories(root.resolve("data/sokar/build").resolve(name));
        Files.createDirectories(root.resolve("data/sokar/projects"));
        final Path file = root.resolve(name + "-project.yml");
        Files.writeString(file, "project:\n  name: \"" + name + "\"\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/sokar/projects").resolve(name), file + "\n",
                StandardCharsets.UTF_8);
    }

    private void runningTask(String container, String project) throws IOException {
        runner.answering("ps", container + "\tUp 4 minutes\n");
        final Path directory = root.resolve("run/sokar").resolve(container);
        Files.createDirectories(directory);
        new Sidecar(Sidecar.VERSION, project, "guarded", directory.resolve("r.nft").toString(),
                directory.resolve("dns.conf").toString(), "/usr/bin/sokar", directory.toString())
                .writeTo(directory.resolve("sidecar.json"));
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    @Test
    void aRefusalDoesNotClaimItRemovedAnything(@TempDir Path dir) throws IOException {

        // Measured while clearing a test machine on 2026-09-12. The report read:
        //
        //     removed   mirror  .../mirrors/accept.git
        //     removed   build   .../build/accept
        //     removed   task    sokar-accept-shell
        //     sokar: task sokar-accept-shell was not removed ... Nothing else has been removed.
        //
        // and the project was still in the listing afterwards. Five lines claiming a removal, one
        // saying none happened, and the reader left to decide which to believe. The list was
        // printed before the outcome was looked at, so its wording followed --dry-run instead of
        // what actually happened.
        final SokarContext context = context(dir);
        project("uc");
        runningTask("sokar-uc-shell", "uc");
        runner.answering("for-each-ref", "");

        final int code = execute(context, "project", "delete", "uc");

        assertThat(code).isEqualTo(65);
        assertThat(out.toString())
                .as("a refusal describes what it WOULD have taken")
                .doesNotContain("removed   ")
                .contains("would remove");
        assertThat(err.toString()).contains("sokar:");
        assertThat(Files.isDirectory(dir.resolve("data/sokar/mirrors/uc.git")))
                .as("and it really did leave it").isTrue();
    }

    @Test
    void aDeletionThatHappenedSaysSoInThePastTense(@TempDir Path dir) throws IOException {

        // The other half. "would remove" on a run that did remove is the same defect mirrored,
        // and it would teach people to ignore the word in both directions.
        final SokarContext context = context(dir);
        project("uc");
        runner.answering("ps", "");
        runner.answering("for-each-ref", "");

        final int code = execute(context, "project", "delete", "uc");

        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("removed   ")
                .doesNotContain("would remove")
                .contains("deleted   uc");
    }

    @Test
    void aPreviewStillSaysWouldAndTouchesNothing(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        project("uc");
        runner.answering("ps", "");
        runner.answering("for-each-ref", "");

        final int code = execute(context, "project", "delete", "uc", "--dry-run");

        assertThat(code).isZero();
        assertThat(out.toString()).contains("would remove").contains("Nothing was removed.");
        assertThat(Files.isDirectory(dir.resolve("data/sokar/mirrors/uc.git"))).isTrue();
    }
}
