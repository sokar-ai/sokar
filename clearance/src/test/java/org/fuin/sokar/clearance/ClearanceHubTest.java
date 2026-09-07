package org.fuin.sokar.clearance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClearanceHub}.
 */
class ClearanceHubTest {

    private static final Blocked ONE_ONE_ONE_ONE = new Blocked("1.1.1.1", 443, "tcp", "1.1.1.1:443");

    private static final Blocked QUAD_NINE = new Blocked("9.9.9.9", 443, "tcp", "9.9.9.9:443");

    private final List<String> allowed = new ArrayList<>();

    private final List<Decision> announced = new ArrayList<>();

    private final AtomicInteger asked = new AtomicInteger();

    private ClearanceHub hub(Verdict answer) {
        final ClearanceHub hub = new ClearanceHub("uc", "shell", request -> {
            asked.incrementAndGet();
            return answer;
        }, allowed::add);
        hub.onDecision(announced::add);
        return hub;
    }

    @Test
    void allowingAddsTheAddress() {

        assertThat(hub(Verdict.ALLOW).handle(ONE_ONE_ONE_ONE)).isEqualTo(Verdict.ALLOW);
        assertThat(allowed).containsExactly("1.1.1.1");
    }

    @Test
    void denyingAddsNothing() {

        hub(Verdict.DENY).handle(ONE_ONE_ONE_ONE);

        assertThat(allowed).isEmpty();
    }

    @Test
    void silenceIsNotConsent() {

        // An operator away from the machine must not open a container's egress by doing nothing.
        assertThat(hub(Verdict.TIMEOUT).handle(ONE_ONE_ONE_ONE).allows()).isFalse();
        assertThat(allowed).isEmpty();
    }

    @Test
    void asksOncePerDestinationHoweverOftenItIsRetried() {

        // A build tool produces hundreds of identical drops a second. One notification each would
        // bury the desktop and make the answer meaningless.
        final ClearanceHub hub = hub(Verdict.ALLOW);
        for (int i = 0; i < 500; i++) {
            hub.handle(ONE_ONE_ONE_ONE);
        }

        assertThat(asked).hasValue(1);
        assertThat(allowed).containsExactly("1.1.1.1");
    }

    @Test
    void doesNotReAskAfterADeny() {

        // Otherwise an agent could extract an allow by retrying until the operator's attention
        // slipped.
        final ClearanceHub hub = hub(Verdict.DENY);
        hub.handle(QUAD_NINE);
        hub.handle(QUAD_NINE);

        assertThat(asked).hasValue(1);
    }

    @Test
    void treatsDifferentDestinationsSeparately() {

        final ClearanceHub hub = hub(Verdict.ALLOW);
        hub.handle(ONE_ONE_ONE_ONE);
        hub.handle(new Blocked("1.1.1.1", 80, "tcp", "1.1.1.1:80"));
        hub.handle(QUAD_NINE);

        assertThat(asked).hasValue(3);
        assertThat(hub.allowed()).hasSize(3);
    }

    @Test
    void aRetryArrivingWhileTheQuestionIsOnScreenDoesNotAskAgain() {

        // The key is recorded as denied before the prompt returns, so a burst during the wait
        // cannot produce a second notification.
        final ClearanceHub[] holder = new ClearanceHub[1];
        holder[0] = new ClearanceHub("uc", "shell", request -> {
            asked.incrementAndGet();
            holder[0].handle(ONE_ONE_ONE_ONE);
            return Verdict.ALLOW;
        }, allowed::add);

        holder[0].handle(ONE_ONE_ONE_ONE);

        assertThat(asked).hasValue(1);
    }

    @Test
    void reportsWhatWasDecided() {

        final ClearanceHub hub = hub(Verdict.ALLOW);
        hub.handle(ONE_ONE_ONE_ONE);

        assertThat(hub.decisions()).containsEntry("tcp/1.1.1.1/443", Verdict.ALLOW);
    }

    @Test
    void announcesEachDecisionOnceWithWhatItWasAbout() {

        final ClearanceHub hub = hub(Verdict.DENY);
        hub.handle(new Blocked("1.1.1.1", 443, "tcp", "api.example.test:443 (1.1.1.1)"));
        hub.handle(new Blocked("1.1.1.1", 443, "tcp", "api.example.test:443 (1.1.1.1)"));

        assertThat(announced).hasSize(1);
        assertThat(announced.get(0).project()).isEqualTo("uc");
        assertThat(announced.get(0).task()).isEqualTo("shell");
        assertThat(announced.get(0).key()).isEqualTo("tcp/1.1.1.1/443");
        assertThat(announced.get(0).port()).isEqualTo(443);
        assertThat(announced.get(0).shown()).isEqualTo("api.example.test:443 (1.1.1.1)");
        assertThat(announced.get(0).verdict()).isEqualTo(Verdict.DENY);
        assertThat(announced.get(0).source()).isEqualTo(Decision.PROMPT);
    }

    @Test
    void announcesAQuestionNobodyAnswered() {

        // The case with nothing else to show for it: the task went on running with that
        // destination blocked, and an unanswered question that is never written down is
        // indistinguishable afterwards from one that was never asked.
        hub(Verdict.TIMEOUT).handle(ONE_ONE_ONE_ONE);

        assertThat(announced).singleElement().satisfies(decision -> {
            assertThat(decision.verdict()).isEqualTo(Verdict.TIMEOUT);
            assertThat(decision.source()).isEqualTo(Decision.PROMPT);
        });
    }

    @Test
    void announcesAnAnswerThatCameFromAClient() {

        final ClearanceHub hub = hub(Verdict.DENY);
        hub.decide("tcp/1.1.1.1/443", "1.1.1.1", Verdict.ALLOW);

        assertThat(allowed).containsExactly("1.1.1.1");
        assertThat(announced).singleElement().satisfies(decision -> {
            assertThat(decision.source()).isEqualTo(Decision.CLIENT);
            assertThat(decision.destination()).isEqualTo("1.1.1.1");
            assertThat(decision.port()).isEqualTo(443);
            assertThat(decision.protocol()).isEqualTo("tcp");
        });
    }

    @Test
    void asksNobodyAboutANameThisRunWasGranted() {

        // Somebody has already said this run may reach it. Asking would put a question on screen
        // whose answer is already known, which is how an operator learns to click without reading.
        final ClearanceHub hub = hub(Verdict.DENY);
        hub.granted("docs.example.test"::equals);

        final Blocked blocked = new Blocked("1.1.1.1", 443, "tcp", "docs.example.test:443",
                "docs.example.test");

        assertThat(hub.handle(blocked)).isEqualTo(Verdict.ALLOW);
        assertThat(asked).hasValue(0);
        assertThat(allowed).containsExactly("1.1.1.1");
    }

    @Test
    void recordsThatNobodyWasAskedAboutAGrantedName() {

        // "Allowed without being asked" and "allowed by somebody" are different events, and the
        // record is the only place that can tell them apart afterwards.
        final ClearanceHub hub = hub(Verdict.DENY);
        hub.granted(name -> true);

        hub.handle(new Blocked("1.1.1.1", 443, "tcp", "docs:443", "docs.example.test"));

        assertThat(announced).singleElement()
                .satisfies(decision -> assertThat(decision.source()).isEqualTo(Decision.GRANTED));
    }

    @Test
    void aGrantDoesNotCoverADestinationNothingResolved() {

        // A grant is made for a name; an address on its own can never match one, and treating it
        // as covered would open a host nobody granted.
        final ClearanceHub hub = hub(Verdict.DENY);
        hub.granted(name -> true);

        hub.handle(ONE_ONE_ONE_ONE);

        assertThat(asked).hasValue(1);
    }

    @Test
    void doesNotAskAgainAboutSomethingAnEarlierWatcherDecided() {

        // A resumed task re-reads its events file from the beginning, so every destination it was
        // ever refused arrives again within seconds. Without the decisions back, retrying until
        // the watcher restarts is all it takes to be asked a second time.
        final ClearanceHub hub = hub(Verdict.ALLOW);
        hub.restore(List.of(decision(QUAD_NINE, Verdict.DENY)));

        assertThat(hub.handle(QUAD_NINE)).isEqualTo(Verdict.DENY);
        assertThat(asked).hasValue(0);
        assertThat(announced).isEmpty();
    }

    @Test
    void putsARestoredAllowBackIntoTheFirewall() {

        // A resumed task gets a fresh namespace and a ruleset rebuilt from the project, so an
        // address the operator cleared last time is no longer in it. Remembering the answer
        // without re-applying it leaves the hub saying allow while the packets are still dropped,
        // and nothing will ask again.
        final ClearanceHub hub = hub(Verdict.DENY);
        hub.restore(List.of(decision(ONE_ONE_ONE_ONE, Verdict.ALLOW)));

        assertThat(allowed).containsExactly("1.1.1.1");
        assertThat(hub.handle(ONE_ONE_ONE_ONE)).isEqualTo(Verdict.ALLOW);
        assertThat(asked).hasValue(0);
    }

    @Test
    void restoringKeepsTheLastAnswerAboutADestination() {

        // A client may answer what the prompt already timed out on, and the file holds both.
        final ClearanceHub hub = hub(Verdict.DENY);
        hub.restore(List.of(decision(ONE_ONE_ONE_ONE, Verdict.TIMEOUT),
                decision(ONE_ONE_ONE_ONE, Verdict.ALLOW)));

        assertThat(hub.decisions()).containsEntry("tcp/1.1.1.1/443", Verdict.ALLOW);
        assertThat(allowed).containsExactly("1.1.1.1");
    }

    private static Decision decision(Blocked blocked, Verdict verdict) {
        return new Decision(Instant.parse("2026-09-07T10:15:30Z"), "uc", "shell", blocked.key(),
                blocked.destination(), blocked.port(), blocked.protocol(), blocked.shown(),
                verdict, Decision.PROMPT);
    }
}
