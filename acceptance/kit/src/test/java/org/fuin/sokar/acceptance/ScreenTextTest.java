package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScreenTextTest {

    @Test
    void readsACursorMoveForwardAsTheSpaceItDraws() {
        // As tmux redrew a real agent's footer, measured: the raw stream never held the words with spaces.
        assertThat(ScreenText.of("bypass\u001b[Cpermissions\u001b[Con\u001b[38;5;246m (s"))
                .contains("bypass permissions on (s");
        assertThat(ScreenText.of("bypass\u001b[39m\u001b[1X\u001b[38;5;211m\u001b[Cpermissions"))
                .isEqualTo("bypass permissions");
    }

    @Test
    void readsACountedMoveAsThatManySpaces() {
        assertThat(ScreenText.of("a\u001b[3Cb")).isEqualTo("a   b");
    }

    @Test
    void dropsTitlesAndOtherControlSequences() {
        assertThat(ScreenText.of("\u001b]0;✳ Title\u0007\u001b[?25l\u001b[1;1HReady\u001b(B")).isEqualTo("Ready");
    }
}
