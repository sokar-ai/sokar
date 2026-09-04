package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link TaskRunCommand}.
 * <p>
 * Everything that touches the machine goes through {@link SokarContext}. An earlier version of
 * this test had none, so running it built images, started containers and then replaced the test
 * process with a shell.
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

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final List<List<String>> execCalls = new ArrayList<>();

    private Path root;

    private SokarContext context(Path dir, boolean hooksInstalled) throws IOException {
        root = dir;
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarPaths paths = new SokarPaths(xdg, dir.resolve("bin"));
        if (hooksInstalled) {
            Files.createDirectories(dir.resolve("bin"));
            for (final String name : List.of("sokar-hook-nft", "sokar-hook-supervisor", "sokar-hook-reader")) {
                final Path binary = dir.resolve("bin").resolve(name);
                Files.writeString(binary, "#!/bin/sh\n");
                binary.toFile().setExecutable(true);
            }
        }
        return new SokarContext(runner, paths, arguments -> {
            execCalls.add(arguments);
            return 0;
        });
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = new CommandLine(new SokarCli(), new SokarFactory(context));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private Path projectFile(Path dir, String content) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void reportsTheResolvedProject(@TempDir Path dir) throws IOException {

        final int code = execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString(), "--dry-run");

        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("project        uc")
                .contains("security class guarded")
                .contains("task image     sokar/uc");
        assertThat(runner.invocations()).as("a dry run must touch nothing").isEmpty();
    }

    @Test
    void refusesToRunWithoutTheHooks(@TempDir Path dir) throws IOException {

        // A container started without its firewall looks completely normal, which is why this
        // must stop rather than warn.
        final int code = execute(context(dir, false), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("run 'sokar setup' first");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void writesThePolicyBeforeCreatingTheContainer(@TempDir Path dir) throws IOException {

        // The nft hook reads both files while the container is being created. Writing them
        // afterwards would leave a window with a container and no firewall.
        execute(context(dir, true), "task", "run", "-p", projectFile(dir, MINIMAL).toString());

        final Path state = root.resolve("run/sokar").resolve(containerName());
        assertThat(state.resolve("ruleset.nft")).exists();
        assertThat(state.resolve("sidecar.json")).exists();
        assertThat(Files.readString(state.resolve("ruleset.nft"))).contains("policy drop;");
    }

    @Test
    void annotatesTheContainerSoTheHooksFire(@TempDir Path dir) throws IOException {

        execute(context(dir, true), "task", "run", "-p", projectFile(dir, MINIMAL).toString());

        assertThat(runner.only("create").describe())
                .contains("--annotation org.fuin.sokar.sidecar=")
                .contains("--security-opt no-new-privileges");
    }

    @Test
    void handsTheTerminalToTheShell(@TempDir Path dir) throws IOException {

        execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString(), "--shell", "/bin/sh");

        assertThat(execCalls).hasSize(1);
        assertThat(execCalls.getFirst())
                .containsExactly("podman", "exec", "--interactive", "--tty", containerName(), "/bin/sh");
    }

    @Test
    void removesTheContainerWhenStartingFails(@TempDir Path dir) throws IOException {

        runner.failing("start", 125, "hook failed");

        final int code = execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(code).isEqualTo(70);
        assertThat(err.toString()).contains("hook failed");
        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        assertThat(execCalls).as("no shell is attached to a container that never started").isEmpty();
    }

    @Test
    void keepsTheContainerWhenAskedTo(@TempDir Path dir) throws IOException {

        runner.failing("start", 125, "hook failed");

        execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString(), "--keep");

        assertThat(runner.lines()).noneMatch(line -> line.contains("rm --force"));
    }

    @Test
    void failsWithoutASubcommand(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true))).isEqualTo(2);
        assertThat(err.toString()).contains("Usage: sokar");
    }

    @Test
    void failsOnAMissingProjectFile(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true), "task", "run", "-p", "/does/not/exist.yml", "--dry-run"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("No project file at /does/not/exist.yml");
    }

    @Test
    void failsOnAnUnreadableProjectFile(@TempDir Path dir) throws IOException {

        final Path file = projectFile(dir, "project:\n  name: uc\n");

        assertThat(execute(context(dir, true), "task", "run", "-p", file.toString(), "--dry-run"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("no 'image' section");
    }

    @Test
    void rejectsAnUnknownOption(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString(), "--nope")).isEqualTo(2);
    }

    private String containerName() {
        return "sokar-uc-shell-" + ProcessHandle.current().pid();
    }

    @Test
    void removesTheContainerWhenTheShellExits(@TempDir Path dir) throws IOException {

        // The bug this exists for: attaching used to replace this process, so nothing was left to
        // remove the container. Every interactive task leaked one while printing the opposite.
        execute(context(dir, true), "task", "run", "-p", projectFile(dir, MINIMAL).toString());

        assertThat(runner.invocations()).anySatisfy(command ->
                assertThat(command.describe()).contains("rm"));
    }

    @Test
    void leavesTheContainerAloneWithKeep(@TempDir Path dir) throws IOException {

        // The negative case: --keep has to mean something, and today it did not.
        execute(context(dir, true), "task", "run", "--keep",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(runner.invocations()).noneSatisfy(command ->
                assertThat(command.describe()).contains("rm "));
    }
}
