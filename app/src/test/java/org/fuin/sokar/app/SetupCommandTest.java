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
        return new SokarContext(new FakeCommandRunner(), paths, arguments -> 0);
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
