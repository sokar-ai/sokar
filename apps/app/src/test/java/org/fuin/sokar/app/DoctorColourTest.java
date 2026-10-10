package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for the colours of {@code sokar doctor}: a failure red, a warning yellow, what is fine green, the words unchanged,
 * and no escape code where nobody sees colour.
 */
class DoctorColourTest {

    private static final String RED = "\u001b[31m";
    private static final String YELLOW = "\u001b[33m";
    private static final String GREEN = "\u001b[32m";

    private static String doctor(final Path dir, final String... arguments) {
        final SokarContext context = new SokarContext(new FakeCommandRunner(),
                new SokarPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin"), dir.resolve("libexec-hooks"),
                        dir.resolve("agents")), command -> 0);
        final StringWriter out = new StringWriter();
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        final String[] all = new String[arguments.length + 1];
        all[0] = "doctor";
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        cmd.execute(all);
        return out.toString();
    }

    @Test
    void aTerminalGetsColourAndAPipeOrNoColorGetsNone() {
        assertThat(DoctorCommand.colours("auto", true, null)).as("a terminal").isTrue();
        assertThat(DoctorCommand.colours("auto", false, null)).as("a pipe or a file").isFalse();
        assertThat(DoctorCommand.colours("auto", true, "1")).as("NO_COLOR").isFalse();
        assertThat(DoctorCommand.colours("auto", true, "")).as("NO_COLOR set, if empty").isFalse();
        assertThat(DoctorCommand.colours("always", false, "1")).as("asked for on the command").isTrue();
        assertThat(DoctorCommand.colours("never", true, null)).as("refused on the command").isFalse();
    }

    @Test
    void aFailureIsRedAWarningYellowAndWhatIsFineGreen() {
        assertThat(DoctorCommand.painted("hooks", Probe.State.MISSING, true)).isEqualTo(RED + "hooks\u001b[0m");
        assertThat(DoctorCommand.painted("dns", Probe.State.DEGRADED, true)).startsWith(YELLOW);
        assertThat(DoctorCommand.painted("dns", Probe.State.UNKNOWN, true)).startsWith(YELLOW);
        assertThat(DoctorCommand.painted("podman", Probe.State.OK, true)).startsWith(GREEN);
        assertThat(DoctorCommand.painted("podman", Probe.State.MISSING, false)).isEqualTo("podman");
    }

    @Test
    void theWordsStayTheSameWithColourOrWithout(@TempDir final Path dir) {
        // On a machine without hooks something is MISSING: the line keeps its word, and only gains the codes.
        final String plain = doctor(dir, "--color=never");
        final String coloured = doctor(dir, "--color=always");

        assertThat(plain).doesNotContain("\u001b[").contains("MISSING");
        assertThat(coloured).contains(RED).contains(GREEN);
        assertThat(coloured.replaceAll("\u001b\\[[0-9;]*m", "")).isEqualTo(plain);
    }

    @Test
    void withoutATerminalNothingIsColoured(@TempDir final Path dir) {
        // The tests run without one, as a pipe does.
        assertThat(doctor(dir)).doesNotContain("\u001b[");
    }
}
