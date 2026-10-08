package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link SetupCommand}.
 */
class SetupCommandTest {

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context(Path dir, boolean withBinaries) throws IOException {
        final XdgPaths xdg = XdgPaths.of(name ->
                "XDG_CONFIG_HOME".equals(name) ? dir.resolve("config").toString() : null, dir);
        final SokarPaths paths = new SokarPaths(xdg, dir.resolve("bin"));
        if (withBinaries) {
            Files.createDirectories(dir.resolve("bin"));
            for (final String name : List.of("sokar-hook-nft", "sokar-hook-supervisor", "sokar-hook-reader")) {
                final Path binary = dir.resolve("bin").resolve(name);
                Files.writeString(binary, "#!/bin/sh\n");
                binary.toFile().setExecutable(true);
            }
        }
        return new SokarContext(runner, paths, arguments -> 0);
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    @Test
    void installsDescriptorsAndTheDropIn(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true), "setup")).isZero();
        assertThat(dir.resolve("config/containers/oci/hooks.d/sokar-hook-nft-createRuntime.json")).exists();
        assertThat(dir.resolve("config/containers/containers.conf.d/50-sokar.conf")).exists();
    }

    @Test
    void startsTheDaemonOnlyWhenItIsNotRunningAndSaysSo(@TempDir Path dir) throws IOException {

        // Each step only when it is not done: a wizard abandoned halfway and run again does not fail on it.
        assertThat(execute(context(dir, true), "setup")).isZero();
        assertThat(out.toString()).contains("daemon    already running");
        assertThat(runner.lines()).noneMatch(line -> line.contains("enable --now"));

        runner.failing("is-active", 3, "");
        out.getBuffer().setLength(0);
        assertThat(execute(context(dir, true), "setup")).isZero();
        assertThat(out.toString()).contains("daemon    started, and starts with this account from now on");
        assertThat(runner.lines()).anyMatch(line -> line.contains("systemctl --user enable --now sokard"));
    }

    @Test
    void saysHowToStartTheDaemonWhenThisSessionCannotWithoutFailingTheRest(@TempDir Path dir) throws IOException {

        runner.failing("is-active", 3, "").failing("enable --now", 1,
                "Failed to connect to bus: No medium found\n");

        assertThat(execute(context(dir, true), "setup")).isZero();
        assertThat(out.toString()).contains("daemon    not started: Failed to connect to bus: No medium found"
                + " - start it in this account's own session with 'systemctl --user enable --now sokard'");
        assertThat(dir.resolve("config/containers/containers.conf.d/50-sokar.conf")).exists();
    }

    @Test
    void leavesTheVaultForATerminalAndSaysSoWhenThereIsNone(@TempDir Path dir) throws IOException {

        // Run the way a script runs it, with no terminal: the prompt would fail as "no passphrase available",
        // which reads as Sokar's fault and is a missing -t.
        assertThat(execute(context(dir, true), "setup")).isZero();
        assertThat(out.toString()).contains("vault     none yet; it needs a passphrase typed at a terminal")
                .contains("ssh -t").contains("Not ready yet").contains("no vault");
    }

    @Test
    void namesAVaultThatIsAlreadyThere(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir, true);
        Files.createDirectories(context.vault().path().getParent());
        Files.writeString(context.vault().path(), "not read, only found");

        assertThat(execute(context, "setup")).isZero();
        assertThat(out.toString()).contains("vault     already there").doesNotContain("no vault");
    }

    @Test
    void registersTheHooksAndNothingElseWhenAskedTo(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true), "setup", "--hooks-only")).isZero();
        assertThat(out.toString()).contains("only fire for containers carrying Sokar's own annotation")
                .doesNotContain("daemon").doesNotContain("vault");
        assertThat(runner.lines()).noneMatch(line -> line.contains("systemctl"));
    }

    @Test
    void refusesWhenTheBinariesAreMissing(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, false), "setup")).isEqualTo(69);
        assertThat(err.toString()).contains("hook binaries are not in");
    }

    @Test
    void uninstallRemovesWhatItInstalled(@TempDir Path dir) throws IOException {

        execute(context(dir, true), "setup");

        assertThat(execute(context(dir, true), "setup", "--uninstall")).isZero();
        assertThat(dir.resolve("config/containers/containers.conf.d/50-sokar.conf")).doesNotExist();
    }
}
