package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.fuin.sokar.machines.Ssh;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Repeated}, with tries and looks that are counted instead of run.
 */
class RepeatedTest {

    private static final Duration SOON = Duration.ofMillis(20);

    /** Reports a loop from its given look on. */
    private static Terminal.Loops loopingFrom(int look) {
        final AtomicInteger looks = new AtomicInteger();
        return new Terminal.Loops() {
            @Override
            public void reset() {
            }

            @Override
            public Optional<String> check() {
                return looks.incrementAndGet() < look ? Optional.empty()
                        : Optional.of("In sokar-busy-busy the tool failure \"✘ Write\" scrolled by 3 times");
            }
        };
    }

    @Test
    void returnsTheFirstTryThatExitsZero() throws IOException {
        final AtomicInteger tries = new AtomicInteger();

        final Ssh.Output done = Repeated.untilZero("sokar talk held", () -> tries.incrementAndGet() < 3
                ? new Ssh.Output("", "nothing yet", 1) : new Ssh.Output("5555", "", 0),
                loopingFrom(Integer.MAX_VALUE), Duration.ofSeconds(240), SOON, UnaryOperator.identity());

        assertThat(done.out()).isEqualTo("5555");
        assertThat(tries).hasValue(3);
    }

    @Test
    void stopsAtOnceWhenAnAgentRepeatsAFailure() {
        final AtomicInteger tries = new AtomicInteger();
        final long began = System.nanoTime();

        assertThatThrownBy(() -> Repeated.untilZero("sokar talk held", () -> {
            tries.incrementAndGet();
            return new Ssh.Output("", "nothing yet", 1);
        }, loopingFrom(2), Duration.ofSeconds(240), SOON, UnaryOperator.identity()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Stopped running \"sokar talk held\"")
                .hasMessageContaining("\"✘ Write\" scrolled by 3 times")
                .hasMessageContaining("nothing yet");

        assertThat(tries).as("no try after the loop was seen").hasValue(2);
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void failsWithTheLastTryWhenTheTimeRunsOut() {
        assertThatThrownBy(() -> Repeated.untilZero("sokar talk held", () -> new Ssh.Output("", "nothing yet", 1),
                loopingFrom(Integer.MAX_VALUE), Duration.ofMillis(200), SOON, UnaryOperator.identity()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not exit zero within 0s")
                .hasMessageContaining("nothing yet");
    }

    @Test
    void aFailureSaysNothingTheFilterHides() {
        assertThatThrownBy(() -> Repeated.untilZero("sokar talk held", () -> new Ssh.Output("", "token abc", 1),
                loopingFrom(1), Duration.ofSeconds(240), SOON, text -> text.replace("abc", "***")))
                .hasMessageNotContaining("abc");
    }
}
