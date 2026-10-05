package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentEnd} as it crosses the agent protocol.
 */
class AgentEndTest {

    @Test
    void anEndCrossesTheWireAndComesBackTheSame() {
        final AgentEnd refused = new AgentEnd(false, "This request requires more credits", "provider", 402);
        final AgentEnd finished = new AgentEnd(true, "", "agent", null);

        assertThat(AgentEnd.of(refused.asMap())).isEqualTo(refused);
        assertThat(AgentEnd.of(finished.asMap())).isEqualTo(finished);
        assertThat(finished.asMap()).doesNotContainKey("status");
    }

    @Test
    void anAgentThatSaysNothingAboutItsEndAnswersNoEnd() {

        // An adapter built before the method answers nothing, as today: no end is reported rather than a wrong one.
        final Agent quiet = new Agent() {
            @Override
            public String name() {
                return "quiet";
            }

            @Override
            public AgentDefinition definition() {
                throw new UnsupportedOperationException();
            }
        };
        assertThat(quiet.ended("{\"type\":\"result\"}")).isNull();
        assertThat(AgentEnd.of(Map.of())).isNull();
    }
}
