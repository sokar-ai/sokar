package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SokarCli}.
 */
class SokarCliTest {

    @Test
    void recognisesAWorkingDirectoryThatHasBeenRemoved() {

        // Unhandled, this reaches the operator as a 30-frame stack trace through sun.nio.
        assertThat(SokarCli.missingWorkingDirectory(
                new Error("Properties init: Could not determine current working directory.")))
                .isTrue();
    }

    @Test
    void looksThroughTheCauseChain() {
        assertThat(SokarCli.missingWorkingDirectory(new RuntimeException("wrapped",
                new Error("Could not determine current working directory."))))
                .isTrue();
    }

    @Test
    void leavesEveryOtherFailureAlone() {

        // Swallowing an unrelated Error would hide a real fault behind advice to cd elsewhere.
        assertThat(SokarCli.missingWorkingDirectory(new OutOfMemoryError("Java heap space")))
                .isFalse();
        assertThat(SokarCli.missingWorkingDirectory(new Error())).isFalse();
        assertThat(SokarCli.missingWorkingDirectory(new Error("no such file"))).isFalse();
    }
}
