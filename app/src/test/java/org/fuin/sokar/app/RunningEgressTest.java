package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.shield.DnsPolicy;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.GrantedNames;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RunningEgress}: giving a task that is already running somewhere new to reach,
 * instead of throwing away an hour of work to change a line in a file.
 */
class RunningEgressTest {

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

    /** A running task with the two files a live widening touches. */
    private Path task(String container, String securityClass) throws IOException {
        runner.answering("ps", container + "\tUp 4 minutes\t1788500000\t0\n");
        final Path state = root.resolve("run/sokar").resolve(container);
        Files.createDirectories(state);
        new Sidecar(Sidecar.VERSION, "uc", securityClass, state.resolve("r.nft").toString(),
                state.resolve("dns.conf").toString(), "/usr/bin/sokar", state.toString())
                .writeTo(state.resolve("sidecar.json"));
        Files.writeString(state.resolve(DnsPolicy.SERVERS_FILE),
                "# written by sokar\nserver=/declared.test/192.0.2.53\n", StandardCharsets.UTF_8);
        Files.writeString(state.resolve("dnsmasq.pid"), "4711", StandardCharsets.UTF_8);
        return state;
    }

    private RunningEgress.Effect widen(SokarContext context, String... names) {
        return new RunningEgress(context).widen("sokar-uc-shell-1", List.of(names),
                RunningEgress.Scope.RUN, false);
    }

    @Test
    void tellsTheResolverAndRecordsTheGrant(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "guarded");

        assertThat(widen(context, "docs.example.test").outcome())
                .isEqualTo(RunningEgress.Outcome.WIDENED);

        // Appended to the file dnsmasq re-reads, with the upstream this task already forwards to.
        assertThat(Files.readString(state.resolve(DnsPolicy.SERVERS_FILE)))
                .contains("server=/docs.example.test/192.0.2.53");
        // And signalled rather than restarted: a restart is a window in which nothing resolves.
        assertThat(runner.lines()).anyMatch(line -> line.equals("kill -HUP 4711"));
        // The watcher reads this and lets the first connection through without asking.
        assertThat(GrantedNames.all(state)).containsExactly("docs.example.test");
    }

    @Test
    void takesTheUpstreamThisTaskAlreadyUses(@TempDir Path dir) throws IOException {

        // Not the host's resolvers as they are now: a task keeps the upstreams it was started
        // with, and the host's may have changed since.
        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "guarded");

        widen(context, "docs.example.test");

        assertThat(Files.readString(state.resolve(DnsPolicy.SERVERS_FILE)))
                .contains("/192.0.2.53");
    }

    @Test
    void refusesATaskThatIsNotRunning(@TempDir Path dir) {

        // Nothing can be changed in a container that is gone, and saying so beats writing files
        // nobody will read.
        final SokarContext context = context(dir);

        assertThat(new RunningEgress(context).widen("sokar-uc-shell-1",
                List.of("docs.example.test"), RunningEgress.Scope.RUN, false).outcome())
                .isEqualTo(RunningEgress.Outcome.NOT_RUNNING);
    }

    @Test
    void refusesAnOfflineProjectAndChangesNothing(@TempDir Path dir) throws IOException {

        // The class is the project's promise that its tasks reach nothing. A run that could step
        // around it at will would make the promise worth nothing.
        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "offline");

        final RunningEgress.Effect effect = widen(context, "docs.example.test");

        assertThat(effect.outcome()).isEqualTo(RunningEgress.Outcome.REFUSED_BY_CLASS);
        assertThat(effect.detail()).contains("offline");
        assertThat(Files.readString(state.resolve(DnsPolicy.SERVERS_FILE)))
                .doesNotContain("docs.example.test");
        assertThat(GrantedNames.all(state)).isEmpty();
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("kill"));
    }

    @Test
    void aPreviewChangesNothingAtAll(@TempDir Path dir) throws IOException {

        // The most consequential edit in the product, and more so against a task that is running
        // than against a file.
        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "guarded");

        final RunningEgress.Effect effect = new RunningEgress(context).widen("sokar-uc-shell-1",
                List.of("docs.example.test"), RunningEgress.Scope.RUN, true);

        assertThat(effect.outcome()).isEqualTo(RunningEgress.Outcome.PREVIEWED);
        assertThat(effect.opens()).containsExactly("docs.example.test");
        assertThat(Files.readString(state.resolve(DnsPolicy.SERVERS_FILE)))
                .doesNotContain("docs.example.test");
        assertThat(GrantedNames.all(state)).isEmpty();
    }

    @Test
    void sayingWhatIsAlreadyGrantedChangesNothing(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        task("sokar-uc-shell-1", "guarded");
        widen(context, "docs.example.test");
        final long signalsAfterTheFirst =
                runner.lines().stream().filter(line -> line.startsWith("kill")).count();

        assertThat(widen(context, "docs.example.test").outcome())
                .isEqualTo(RunningEgress.Outcome.NO_CHANGE);
        // The resolver is not signalled a second time for something it was already told.
        assertThat(runner.lines().stream().filter(line -> line.startsWith("kill")).count())
                .isEqualTo(signalsAfterTheFirst);
    }

    @Test
    void writesTheProjectFileTooWhenAskedTo(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        task("sokar-uc-shell-1", "guarded");
        final Path projectFile = project(dir);

        final RunningEgress.Effect effect = new RunningEgress(context).widen("sokar-uc-shell-1",
                List.of("docs.example.test"), RunningEgress.Scope.RUN_AND_PROJECT, false);

        assertThat(effect.outcome()).isEqualTo(RunningEgress.Outcome.WIDENED);
        assertThat(effect.persisted()).isTrue();
        assertThat(Files.readString(projectFile)).contains("docs.example.test");
    }

    @Test
    void saysTheRunWasWidenedEvenWhenTheFileCouldNotBe(@TempDir Path dir) throws IOException {

        // The lie in the more dangerous direction would be reporting a failure: somebody would
        // believe nothing had changed, while the running task can now reach the host.
        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "guarded");

        final RunningEgress.Effect effect = new RunningEgress(context).widen("sokar-uc-shell-1",
                List.of("docs.example.test"), RunningEgress.Scope.RUN_AND_PROJECT, false);

        assertThat(effect.outcome()).isEqualTo(RunningEgress.Outcome.NO_PROJECT_FILE);
        assertThat(effect.persisted()).isFalse();
        assertThat(effect.detail()).contains("the run was widened");
        assertThat(GrantedNames.all(state)).containsExactly("docs.example.test");
    }

    /** A project file, and the registry entry that lets the daemon find it by project name. */
    private Path project(Path dir) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """, StandardCharsets.UTF_8);
        final Path entry = dir.resolve("data/sokar/projects/uc");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, file + "\n", StandardCharsets.UTF_8);
        return file;
    }
}
