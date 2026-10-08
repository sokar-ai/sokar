package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HelperEnvironment}: what of the caller's environment reaches the filter and a transport.
 */
class HelperEnvironmentTest {

    @Test
    void onlyWhatAProcessNeedsPassesAndNothingThatSteersAHelper() {
        final Map<String, String> caller = Map.of("PATH", "/usr/bin", "HOME", "/home/a", "XDG_RUNTIME_DIR",
                "/run/user/1000", "SOKAR_MSGSLUICE_DECISION_DISABLEDRULES", "ENCODING/BASE64",
                "SOKAR_MATRIX_TLS_VERIFY", "off", "CLAUDECODE", "1", "CLAUDE_CODE_SESSION_ID", "s-1");

        assertThat(HelperEnvironment.base(caller)).containsOnlyKeys("PATH", "HOME", "XDG_RUNTIME_DIR");
    }

    @Test
    void aChosenEnvironmentReallyStartsTheProcessWithoutTheCallersOwn() {
        // Through the real runner: 'env' prints what the child got.
        final String printed = new ProcessCommandRunner(java.time.Duration.ofSeconds(10)).runOrFail(
                Command.of("env").withEnvironment(Map.of("HANDED", "yes"))
                        .withChosenEnvironment(Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin"))))
                .standardOutput();

        assertThat(printed).contains("HANDED=yes").contains("PATH=");
        assertThat(printed.lines().filter(line -> line.startsWith("HOME=") || line.startsWith("USER="))).isEmpty();
    }
}
