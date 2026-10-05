package org.fuin.sokar.core.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProcessCommandRunner}.
 */
class ProcessCommandRunnerTest {

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(20));

    @Test
    void capturesStandardOutput() {

        final CommandResult result = runner.run(Command.of("echo", "hello"));

        assertThat(result.successful()).isTrue();
        assertThat(result.trimmedOutput()).isEqualTo("hello");
    }

    @Test
    void capturesStandardErrorAndTheExitCode() {

        final CommandResult result = runner.run(Command.of("sh", "-c", "echo boom >&2; exit 3"));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.standardError()).contains("boom");
        assertThat(result.successful()).isFalse();
    }

    @Test
    void writesStandardInput() {

        final CommandResult result = runner.run(Command.of("cat").withInput("piped"));

        assertThat(result.trimmedOutput()).isEqualTo("piped");
    }

    @Test
    void doesNotDeadlockOnOutputLargerThanAPipeBuffer() {

        // A pipe buffer is 64 KiB. Without a reader draining it, this hangs instead of failing,
        // which is why the drain exists at all.
        final CommandResult result = runner.run(Command.of("sh", "-c", "yes abcdefgh | head -c 500000"));

        assertThat(result.standardOutput()).hasSize(500000);
    }

    @Test
    void reportsTheProgramsOwnErrorOutputWhenFailing() {

        assertThatThrownBy(() -> runner.runOrFail(Command.of("sh", "-c", "echo 'no such image' >&2; exit 125")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("exit code 125")
                .hasMessageContaining("no such image");
    }

    @Test
    void reportsAProgramThatCannotBeStarted() {

        assertThatThrownBy(() -> runner.run(Command.of("sokar-no-such-program")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Cannot run");
    }

    @Test
    void killsAProgramThatOutlivesItsTimeout() {

        final CommandRunner impatient = new ProcessCommandRunner(Duration.ofMillis(200));

        assertThatThrownBy(() -> impatient.run(Command.of("sleep", "30")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Timed out");
    }

    @org.junit.jupiter.api.Test
    void aChildThatLeavesAGrandchildHoldingItsOutputDoesNotHoldTheCallerToo() {

        // Only the child was ended, and the output was then waited for without a limit: a grandchild that kept it
        // open - a git helper under 'git fetch' against an upstream that feeds slowly - held the caller for ever.
        final java.time.Instant started = java.time.Instant.now();
        new ProcessCommandRunner(java.time.Duration.ofSeconds(1)).run(Command.of("sh", "-c", "sleep 8 & exit 0"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProcessCommandRunner(java.time.Duration.ofSeconds(1))
                .run(Command.of("sh", "-c", "sleep 8 & sleep 8"))).isInstanceOf(CommandException.class);

        org.assertj.core.api.Assertions.assertThat(java.time.Duration.between(started, java.time.Instant.now()))
                .isLessThan(java.time.Duration.ofSeconds(6));
    }

    @org.junit.jupiter.api.Test
    void aChildThatDoesNotReadItsInputStillSucceeds() {

        // The input was written before the limit ran, and a child that exited without reading it made a successful
        // command fail with "Stream closed".
        org.assertj.core.api.Assertions.assertThat(new ProcessCommandRunner(java.time.Duration.ofSeconds(5))
                .run(Command.of("true").withInput("x".repeat(1024 * 1024))).exitCode()).isZero();
    }

    @org.junit.jupiter.api.Test
    void outputBeyondItsLimitIsKeptAsItsEndNotHeldWhole() {

        // Collected without a limit, what a hostile server made git print could run the daemon out of memory.
        final CommandResult result = new ProcessCommandRunner(java.time.Duration.ofSeconds(30))
                .run(Command.of("sh", "-c", "head -c 40000000 /dev/zero | tr '\\0' 'x'; echo END"));

        org.assertj.core.api.Assertions.assertThat(result.standardOutput().length()).isLessThan(20_000_000);
        org.assertj.core.api.Assertions.assertThat(result.standardOutput()).contains("cut").endsWith("END\n");
    }
}
