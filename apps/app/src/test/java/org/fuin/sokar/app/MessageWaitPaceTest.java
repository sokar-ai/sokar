package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link MessageWatch#pause}: a loop that waits on a transport never asks it again without a pause when the
 * transport did not wait.
 */
class MessageWaitPaceTest {

    private static TransportConversations.Outcome outcome(final Map<String, String> handed, final List<String> failed) {
        return new TransportConversations.Outcome(handed, 0, List.of(), failed);
    }

    @Test
    void aWaitThatCameBackAtOnceWithNothingIsFollowedByAPause() {

        // Two tasks down for two days, no account for them in the vault; each direct-chat loop
        // came back from its 'wait' at once, with nothing and no error, and asked again - two cores, for hours.
        assertThat(MessageWatch.pause(outcome(Map.of(), List.of()), Duration.ofMillis(3)))
                .isEqualTo(MessageWatch.IDLE);
    }

    @Test
    void aWaitThatWaitedOrBroughtSomethingIsAskedAgainAtOnceAndAFailureAfterAPause() {
        assertThat(MessageWatch.pause(outcome(Map.of(), List.of()), Duration.ofSeconds(30))).isZero();
        assertThat(MessageWatch.pause(outcome(Map.of("m", "t"), List.of()), Duration.ofMillis(3))).isZero();
        assertThat(MessageWatch.pause(outcome(Map.of(), List.of("refused")), Duration.ofMillis(3)))
                .isEqualTo(MessageWatch.IDLE);
    }
}
