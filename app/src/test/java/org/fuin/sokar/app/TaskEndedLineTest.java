package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the line {@code sokar task status} says about how a task's agent ended.
 */
class TaskEndedLineTest {

    @Test
    void aProvidersRefusalIsNamedAsTheProvidersWithItsStatusAndText() {

        // The person must know to look at the provider - credits, key, limits - and not at Sokar.
        assertThat(TaskStatusCommand.endedLine(new AgentEnding.Ended("2026-10-03T12:57:30Z", false,
                "This request requires more credits", "provider", 402, 0)))
                .isEqualTo("with an error at 2026-10-03T12:57:30Z: 402 from the provider - This request requires more"
                        + " credits");
    }

    @Test
    void aFinishedRunThatLeftWorkUnpushedSaysSoAndWhatToDo() {

        // It wrote its report and ended before pushing it: 'idle' pointed nowhere.
        assertThat(TaskStatusCommand.endedLine(new AgentEnding.Ended("2026-10-03T13:05:00Z", true, "", "agent", null,
                1))).isEqualTo("finished at 2026-10-03T13:05:00Z; 1 change never pushed to the gate - 'sokar task"
                        + " remove TASK --rescue' pushes it there");
        assertThat(TaskStatusCommand.endedLine(new AgentEnding.Ended("t", false, "error_max_turns", "agent", null, 0)))
                .isEqualTo("with an error at t: the agent - error_max_turns");
    }
}
