package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link PanicCommand}: the one action that stops everything, and removes nothing.
 */
class PanicCommandTest {

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private SokarContext context(Path dir) {
        root = dir;
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private void stateOf(String container) throws IOException {
        final Path state = root.resolve("run/sokar").resolve(container);
        Files.createDirectories(state);
        new Sidecar(Sidecar.VERSION, "uc", "guarded", state.resolve("r.nft").toString(),
                state.resolve("dns.conf").toString(), "/usr/bin/sokar", state.toString())
                .writeTo(state.resolve("sidecar.json"));
    }

    @Test
    void stopsEveryRunningTaskWithoutBeingToldTheirNames(@TempDir Path dir) throws IOException {

        // Somebody reaching for this is not in a position to list what is running first.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\nsokar-uc-build-2\tUp 2 hours\n");
        stateOf("sokar-uc-shell-1");
        stateOf("sokar-uc-build-2");

        assertThat(execute(context, "panic")).isZero();

        assertThat(runner.lines()).anyMatch(line ->
                line.startsWith("podman stop") && line.endsWith("sokar-uc-shell-1"));
        assertThat(runner.lines()).anyMatch(line ->
                line.startsWith("podman stop") && line.endsWith("sokar-uc-build-2"));
        assertThat(out.toString()).contains("stopped   2 of 2 tasks");
    }

    @Test
    void removesNothing(@TempDir Path dir) throws IOException {

        // The reason for reaching for this is that something is going wrong and nobody yet knows
        // what, which is exactly when destroying the evidence is worst.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "panic")).isZero();

        assertThat(runner.lines()).noneMatch(line -> line.contains("rm --force"));
        // 'task resume' is gone; what brings a task back is attaching to it, which offers to
        // start it. The message named a verb the CLI refuses until 2026-09-12.
        assertThat(out.toString()).contains("kept").contains("task attach");
    }

    @Test
    void leavesAStoppedTaskAlone(@TempDir Path dir) throws IOException {

        // It is already stopped; touching it again would only produce noise in the one report an
        // operator is reading to find out what is still up.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 3 minutes ago\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "panic")).isZero();

        assertThat(out.toString()).contains("nothing is running");
        assertThat(runner.lines()).noneMatch(line -> line.contains("podman stop"));
    }

    @Test
    void saysSoWhenThereIsNothingToStop(@TempDir Path dir) {

        assertThat(execute(context(dir), "panic")).isZero();
        assertThat(out.toString()).contains("nothing is running");
    }

    @Test
    void listsWhatItWouldStopWithoutStoppingIt(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "panic", "--dry-run")).isZero();

        assertThat(out.toString()).contains("would stop sokar-uc-shell-1");
        assertThat(runner.lines()).noneMatch(line -> line.contains("podman stop"));
    }

    @Test
    void touchesNothingThatIsNotATask(@TempDir Path dir) throws IOException {

        // A container somebody else started is not Sokar's to stop, whatever is going wrong.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\nnot-sokar-at-all\tUp 2 hours\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "panic")).isZero();

        assertThat(runner.lines()).noneMatch(line -> line.contains("not-sokar-at-all"));
        assertThat(out.toString()).contains("stopped   1 of 1 tasks");
    }
}
