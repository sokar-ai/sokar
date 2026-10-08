package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for what a review prints: the agent's words, never its terminal controls.
 */
class ReviewOutputTest {

    @Test
    void aSubjectOrALineOfThePatchCannotRewriteWhatThePersonReads() {

        // Printed raw, a subject or a patch line carrying ESC[1A ESC[2K erased the lines before it - the warning
        // to read first among them - and the person approved what they had not seen.
        final String said = GateReviewCommand.shown("line one\n\u001b[1A\u001b[2Kall is well\n\tindented\n");

        assertThat(said).doesNotContain("\u001b").contains("\\x1b[1A").contains("line one\n").contains("\n\tindented");
    }
}
