package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
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
                new SokarPaths(xdg, hooks, packagedHooks, packagedAgents), arguments -> 0);
    }

    private String doctor(SokarContext context) {
        final CommandLine cmd = SokarCli.commandLine(context);
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
    void everyFailureNamesTheOneThingToDoAboutIt(@TempDir Path dir) {

        // The rule this report lives or dies by, checked over the whole list rather than trusted
        // per line. Nothing is faked here, so which probes fail depends on the machine - what is
        // asserted is that whichever ones do, they say what to do.
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(context(dir, dir.resolve("bin"), dir.resolve("libexec-hooks"),
                dir.resolve("agents")));

        assertThat(doctor.probes()).isNotEmpty().allSatisfy(probe ->
                assertThat(probe.healthy() || !probe.action().isBlank()).isTrue());
    }

    @Test
    void refusesAProbeThatFailsWithNothingToDoAboutIt() {

        // Enforced in the type, because the line somebody forgets is the line an operator is
        // reading at their worst moment.
        assertThatThrownBy(() -> new Probe("nft", Probe.State.MISSING, "not installed", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no next action");
    }

    @Test
    void probesEveryExternalDependencyByName(@TempDir Path dir) {

        // Each of these fails somewhere far from itself: no ruleset, no mirror, no cached
        // passphrase, a name that resolves and then does not connect.
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(context(dir, dir.resolve("bin"), dir.resolve("libexec-hooks"),
                dir.resolve("agents")));

        assertThat(doctor.probes()).extracting(Probe::name)
                .contains("podman", "hooks registered", "rootless network", "dnsmasq nftset",
                        "nft", "git", "nsenter", "keyring", "selinux policy", "transports",
                        "message filter", "configuration key", "following");
    }

    /**
     * A machine with no transport carries no message anywhere. That is a state worth reporting
     * rather than a fault: nothing is broken, and nothing will arrive either.
     */
    @Test
    void saysWhenNoTransportIsInstalled(@TempDir Path dir) {

        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            default -> null;
        }, dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0));

        assertThat(doctor.probes()).filteredOn(probe -> "transports".equals(probe.name()))
                .singleElement().satisfies(probe -> {
                    assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                    assertThat(probe.detail()).contains("none installed");
                });
    }

    /**
     * Its own line, because the two silences are different: with no transport a message reaches no
     * peer, and with no filter nothing is sent at all. A machine with every transport installed and
     * no filter is mute, and a list of green lines must not read as ready.
     */
    @Test
    void saysWhenNoMessageFilterIsInstalled(@TempDir Path dir) {

        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            default -> null;
        }, dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0));

        assertThat(doctor.probes()).filteredOn(probe -> "message filter".equals(probe.name()))
                .singleElement().satisfies(probe -> {
                    assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                    assertThat(probe.detail()).contains("nothing a task writes ever leaves");
                    assertThat(probe.action()).contains(SokarPaths.MESSAGE_FILTER);
                });
    }

    /**
     * A machine with nothing pinned does not break - it stops following its projects, which is the
     * kind of failure that looks like nothing at all.
     */
    @Test
    void saysWhenNoConfigurationKeyIsPinned(@TempDir Path dir) {

        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            default -> null;
        }, dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0));

        assertThat(doctor.probes()).filteredOn(probe -> "configuration key".equals(probe.name()))
                .singleElement().satisfies(probe -> {
                    assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                    assertThat(probe.detail()).contains("none pinned");
                });
    }

    /**
     * An adapter that is installed and does not answer is worse than one that is absent: a peer
     * configured against it looks configured.
     */
    @Test
    void saysWhenAnInstalledTransportDoesNotAnswer(@TempDir Path dir) throws java.io.IOException {

        final java.nio.file.Path transports = dir.resolve("data").resolve("sokar")
                .resolve("transports");
        java.nio.file.Files.createDirectories(transports);
        final java.nio.file.Path adapter =
                transports.resolve(TransportDirectory.PREFIX + "local");
        java.nio.file.Files.writeString(adapter, "#!/bin/sh\nexit 3\n");
        java.nio.file.Files.setPosixFilePermissions(adapter,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            default -> null;
        }, dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(new SokarContext(
                new FakeCommandRunner().failing("describe", 3, "no"),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0));

        assertThat(doctor.probes()).filteredOn(probe -> "transports".equals(probe.name()))
                .singleElement().satisfies(probe -> {
                    assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                    assertThat(probe.detail()).contains("local did not answer");
                });
    }

    @Test
    void saysWhenABackendCannotMapTheHostsLoopback(@TempDir Path dir) {

        // It works, and the git gate ends up on the operator's network with only its per-task
        // token in front of it. Degraded rather than missing: the machine runs tasks.
        final FakeCommandRunner runner = new FakeCommandRunner()
                .answering("RootlessNetworkCmd", "slirp4netns\n");
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final DoctorCommand doctor = new DoctorCommand();
        doctor.setContext(new SokarContext(runner,
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0));

        assertThat(doctor.probes())
                .filteredOn(probe -> "rootless network".equals(probe.name()))
                .singleElement()
                .satisfies(probe -> {
                    assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
                    assertThat(probe.detail()).contains("binds every interface");
                    assertThat(probe.action()).contains("passt");
                });
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

        // Both halves: an operator told only that a copy is unused goes looking for the one that
        // is used.
        assertThat(report).contains("not used " + packaged);
        assertThat(report).contains("hidden by " + own);
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
        assertThat(report).contains("hidden by "
                + dir.resolve(".local/share/sokar/agents").resolve(AGENT));
    }
}
