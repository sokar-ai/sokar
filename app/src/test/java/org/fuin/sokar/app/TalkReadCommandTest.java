package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Test for what {@link TalkReadCommand} lets reach a terminal.
 */
class TalkReadCommandTest {

    @Test
    void writesAControlSequenceOutInsteadOfLettingItAct() {
        // A message built to exercise it: a title change, a cursor move and a screen clear.
        assertThat(TalkReadCommand.shown("hi\u001b]0;owned\u0007\u001b[2J\u001b[H"))
                .isEqualTo("hi\\x1b]0;owned\\x07\\x1b[2J\\x1b[H");
    }

    @Test
    void writesOutTheSingleByteIntroducerAndDelete() {
        assertThat(TalkReadCommand.shown("a\u009b31mb\u007f")).isEqualTo("a\\x9b31mb\\x7f");
    }

    @Test
    void leavesTextTabsAndOtherScriptsAsTheyAre() {
        assertThat(TalkReadCommand.shown("Can you\treview it? – Grüße, 日本")).isEqualTo("Can you\treview it? – Grüße, 日本");
    }
}
