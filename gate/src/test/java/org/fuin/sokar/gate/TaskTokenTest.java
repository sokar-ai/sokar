package org.fuin.sokar.gate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TaskToken}, which authenticates every push an agent makes.
 */
class TaskTokenTest {

    @Test
    void showsEnoughToRecogniseItAndNotEnoughToUseIt() {

        // The gate used to print the whole token into gate.log, which the daemon streams to
        // whatever is tailing it - defeating the toString below, which exists for that reason.
        final TaskToken token = TaskToken.mint();

        assertThat(token.abbreviate()).hasSize(7).endsWith("...");
        assertThat(token.value()).doesNotContain(token.abbreviate());
        assertThat(token.value()).startsWith(token.abbreviate().substring(0, 4));
    }

    @Test
    void keepsItselfOutOfAnyLineItLandsIn() {

        final TaskToken token = TaskToken.mint();

        assertThat(token.toString()).doesNotContain(token.value());
    }

    @Test
    void abbreviatesATokenShorterThanTheAbbreviation() {

        // Only a hand-supplied one can be this short - the environment carries it between the task
        // and the gate - and substring would throw rather than say so.
        assertThat(new TaskToken("ab").abbreviate()).isEqualTo("ab...");
    }

    @Test
    void comparesInConstantTime() {

        final TaskToken token = TaskToken.mint();

        assertThat(token.matches(token.value())).isTrue();
        assertThat(token.matches("something else")).isFalse();
    }

    @Test
    void mintsADifferentTokenEveryTime() {

        assertThat(TaskToken.mint().value()).isNotEqualTo(TaskToken.mint().value());
    }
}
