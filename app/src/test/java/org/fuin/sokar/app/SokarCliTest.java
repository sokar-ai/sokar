package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SokarCli}.
 */
class SokarCliTest {

    @Test
    void recognisesAWorkingDirectoryThatHasBeenRemoved() {

        // What GraalVM raises when the shell is parked in a deleted directory. Left unhandled it
        // reaches the operator as a 30-frame stack trace through sun.nio internals, which says
        // nothing about the one thing they need to do: cd somewhere else.
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

        // The case that matters more than the positive one: swallowing an unrelated Error would
        // hide a real fault behind advice to change directory.
        assertThat(SokarCli.missingWorkingDirectory(new OutOfMemoryError("Java heap space")))
                .isFalse();
        assertThat(SokarCli.missingWorkingDirectory(new Error())).isFalse();
        assertThat(SokarCli.missingWorkingDirectory(new Error("no such file"))).isFalse();
    }
}
