package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link TaskRunCommand}.
 * <p>
 * Exit codes are asserted, not just output: they are the contract a script depends on.
 */
class TaskRunCommandTest {

    private static final String MINIMAL = """
            project:
              name: "uc"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private int execute(String... args) {
        final CommandLine cmd = new CommandLine(new SokarCli());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private Path projectFile(@TempDir Path dir, String content) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void reportsTheResolvedProject(@TempDir Path dir) throws IOException {

        final int code = execute("task", "run", "-p", projectFile(dir, MINIMAL).toString(), "--dry-run");

        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("project        uc")
                .contains("security class guarded")
                .contains("base image     ubuntu:24.04")
                .contains("task image     sokar/uc");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void failsWithoutASubcommand() {

        assertThat(execute()).isEqualTo(2);
        assertThat(err.toString()).contains("Usage: sokar");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void failsOnAMissingProjectFile() {

        assertThat(execute("task", "run", "-p", "/does/not/exist.yml", "--dry-run")).isEqualTo(2);
        assertThat(err.toString()).contains("No project file at /does/not/exist.yml");
    }

    @Test
    void failsOnAnUnreadableProjectFile(@TempDir Path dir) throws IOException {

        final Path file = projectFile(dir, "project:\n  name: uc\n");

        assertThat(execute("task", "run", "-p", file.toString(), "--dry-run")).isEqualTo(2);
        assertThat(err.toString()).contains("no 'image' section");
    }

    @Test
    void refusesToPretendItCanLaunchAContainer(@TempDir Path dir) throws IOException {

        // Until the runtime slice lands, a plain run must fail loudly rather than exit zero
        // having done nothing.
        final int code = execute("task", "run", "-p", projectFile(dir, MINIMAL).toString());

        assertThat(code).isEqualTo(70);
        assertThat(err.toString()).contains("not implemented yet");
    }

    @Test
    void rejectsAnUnknownOption(@TempDir Path dir) throws IOException {

        assertThat(execute("task", "run", "-p", projectFile(dir, MINIMAL).toString(), "--nope"))
                .isEqualTo(2);
    }
}
