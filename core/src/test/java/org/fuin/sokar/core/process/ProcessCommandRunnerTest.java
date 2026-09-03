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
}
