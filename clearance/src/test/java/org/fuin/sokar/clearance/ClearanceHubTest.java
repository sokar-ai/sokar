package org.fuin.sokar.clearance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClearanceHub}.
 */
class ClearanceHubTest {

    private final List<String> allowed = new ArrayList<>();

    private final AtomicInteger asked = new AtomicInteger();

    private ClearanceHub hub(Verdict answer) {
        return new ClearanceHub("uc", request -> {
            asked.incrementAndGet();
            return answer;
        }, allowed::add);
    }

    @Test
    void allowingAddsTheAddress() {

        assertThat(hub(Verdict.ALLOW).handle("tcp/1.1.1.1/443", "1.1.1.1", "1.1.1.1:443", "tcp"))
                .isEqualTo(Verdict.ALLOW);
        assertThat(allowed).containsExactly("1.1.1.1");
    }

    @Test
    void denyingAddsNothing() {

        hub(Verdict.DENY).handle("tcp/1.1.1.1/443", "1.1.1.1", "1.1.1.1:443", "tcp");

        assertThat(allowed).isEmpty();
    }

    @Test
    void silenceIsNotConsent() {

        // An operator away from the machine must not open a container's egress by doing nothing.
        assertThat(hub(Verdict.TIMEOUT).handle("tcp/1.1.1.1/443", "1.1.1.1", "1.1.1.1:443", "tcp").allows())
                .isFalse();
        assertThat(allowed).isEmpty();
    }

    @Test
    void asksOncePerDestinationHoweverOftenItIsRetried() {

        // A build tool produces hundreds of identical drops a second. One notification each would
        // bury the desktop and make the answer meaningless.
        final ClearanceHub hub = hub(Verdict.ALLOW);
        for (int i = 0; i < 500; i++) {
            hub.handle("tcp/1.1.1.1/443", "1.1.1.1", "1.1.1.1:443", "tcp");
        }

        assertThat(asked).hasValue(1);
        assertThat(allowed).containsExactly("1.1.1.1");
    }

    @Test
    void doesNotReAskAfterADeny() {

        // Otherwise an agent could extract an allow by retrying until the operator's attention
        // slipped.
        final ClearanceHub hub = hub(Verdict.DENY);
        hub.handle("tcp/9.9.9.9/443", "9.9.9.9", "9.9.9.9:443", "tcp");
        hub.handle("tcp/9.9.9.9/443", "9.9.9.9", "9.9.9.9:443", "tcp");

        assertThat(asked).hasValue(1);
    }

    @Test
    void treatsDifferentDestinationsSeparately() {

        final ClearanceHub hub = hub(Verdict.ALLOW);
        hub.handle("tcp/1.1.1.1/443", "1.1.1.1", "1.1.1.1:443", "tcp");
        hub.handle("tcp/1.1.1.1/80", "1.1.1.1", "1.1.1.1:80", "tcp");
        hub.handle("tcp/9.9.9.9/443", "9.9.9.9", "9.9.9.9:443", "tcp");

        assertThat(asked).hasValue(3);
        assertThat(hub.allowed()).hasSize(3);
    }

    @Test
    void aRetryArrivingWhileTheQuestionIsOnScreenDoesNotAskAgain() {

        // The key is recorded as denied before the prompt returns, so a burst during the wait
        // cannot produce a second notification.
        final ClearanceHub[] holder = new ClearanceHub[1];
        holder[0] = new ClearanceHub("uc", request -> {
            asked.incrementAndGet();
            holder[0].handle("tcp/1.1.1.1/443", "1.1.1.1", "1.1.1.1:443", "tcp");
            return Verdict.ALLOW;
        }, allowed::add);

        holder[0].handle("tcp/1.1.1.1/443", "1.1.1.1", "1.1.1.1:443", "tcp");

        assertThat(asked).hasValue(1);
    }

    @Test
    void reportsWhatWasDecided() {

        final ClearanceHub hub = hub(Verdict.ALLOW);
        hub.handle("tcp/1.1.1.1/443", "1.1.1.1", "1.1.1.1:443", "tcp");

        assertThat(hub.decisions()).containsEntry("tcp/1.1.1.1/443", Verdict.ALLOW);
    }
}
