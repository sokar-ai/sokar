package org.fuin.sokar.clearance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a settled clearance event carries.
 * <p>
 * Written because a field was declared on the wire and not sent: the interface description names
 * {@code at}, {@code prefix} and {@code deadline} on a prompt, and an event that omits one leaves a
 * client reading nothing where the contract promised something. Nothing else here would notice.
 */
class DecisionTest {

    private static Decision decision() {
        return new Decision(Instant.parse("2026-09-11T05:00:00Z"), "demo", "sokar-demo-shell-1",
                "tcp/1.2.3.4/443", "1.2.3.4", 443, "tcp", "example.org:443 (1.2.3.4)",
                Verdict.ALLOW, Decision.PROMPT);
    }

    @Test
    void carriesADeadlineFieldEvenThoughNothingIsWaitingAnyMore() {

        // Empty rather than absent. The field is not optional on the wire, so a client reading it
        // off a settled event has to find something there - and a moment would be worse than
        // nothing, because a countdown to it would be counting to something already decided.
        final Map<String, Object> event = decision().event();

        assertThat(event).containsKey("deadline");
        assertThat(event.get("deadline")).isEqualTo("");
    }

    @Test
    void carriesEveryFieldAnInterfaceIsToldToExpect() {

        // The names the interface description declares on Prompt. A settled event is the same
        // destination arriving again, so it has to answer to the same fields.
        assertThat(decision().event()).containsKeys("key", "destination", "protocol", "port",
                "at", "deadline", "verdict");
    }
}
