package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link DoctorCommand}, mostly about which binaries it says are in use.
 * <p>
 * The packaged locations are injected: read from the machine, these would pass or fail depending
 * on whether Sokar happens to be installed on it.
 */
class DoctorCommandTest {

    private final StringWriter out = new StringWriter();

    /** An agent name that is not a real one - no name of a real agent may appear outside agents/. */
    private static final String AGENT = AgentDirectory.PREFIX + "example";

    private SokarContext context(Path dir, Path hooks, Path packagedHooks, Path packagedAgents) {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        return new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, hooks, packagedHooks, packagedAgents), arguments -> { });
    }

    private String doctor(SokarContext context) {
        final CommandLine cmd = new CommandLine(new SokarCli(), new SokarFactory(context));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        cmd.execute("doctor");
        return out.toString();
    }

    /**
     * Writes an executable agent binary.
     *
     * @param directory Where to write it.
     * @return The file.
     * @throws IOException If it cannot be written.
     */
    private static Path agent(Path directory) throws IOException {
        Files.createDirectories(directory);
        final Path file = Files.createFile(directory.resolve(AGENT));
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    @Test
    void namesWhereTheHooksAndAgentsComeFrom(@TempDir Path dir) throws IOException {

        final Path hooks = Files.createDirectory(dir.resolve("bin"));
        final Path packagedAgents = Files.createDirectory(dir.resolve("libexec-agents"));

        final String report = doctor(
                context(dir, hooks, dir.resolve("libexec-hooks"), packagedAgents));

        assertThat(report).contains("hooks    " + hooks);
        assertThat(report).contains("agents   " + dir.resolve(".local/share/sokar/agents"));
        assertThat(report).contains("agents   " + packagedAgents);
    }

    @Test
    void saysNothingAboutShadowingOnAPlainInstall(@TempDir Path dir) throws IOException {

        // A line that is always printed is a line nobody reads.
        final Path packaged = Files.createDirectory(dir.resolve("libexec-hooks"));
        Files.writeString(packaged.resolve("sokar-hook-nft"), "");

        final String report = doctor(
                context(dir, packaged, packaged, Files.createDirectory(dir.resolve("agents"))));

        assertThat(report).doesNotContain("not used");
    }

    @Test
    void namesThePackagedHooksALocalBuildHides(@TempDir Path dir) throws IOException {

        final Path own = Files.createDirectory(dir.resolve("bin"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec-hooks"));
        Files.writeString(own.resolve("sokar-hook-nft"), "");
        Files.writeString(packaged.resolve("sokar-hook-nft"), "");

        final String report = doctor(
                context(dir, own, packaged, Files.createDirectory(dir.resolve("agents"))));

        assertThat(report).contains("not used " + packaged);
        assertThat(report).contains("a copy of your own is used instead");
    }

    @Test
    void namesThePackagedAgentALocalBuildHides(@TempDir Path dir) throws IOException {

        // Found on a real machine: yesterday's build in the data directory, and nothing said so.
        agent(dir.resolve(".local/share/sokar/agents"));
        final Path packagedAgents = dir.resolve("libexec-agents");
        final Path theirs = agent(packagedAgents);

        final String report = doctor(
                context(dir, dir.resolve("bin"), dir.resolve("libexec-hooks"), packagedAgents));

        assertThat(report).contains("not used " + theirs);
    }
}
