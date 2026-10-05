package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for how a {@link Terminal} waits, on streams instead of a machine.
 */
class TerminalTest {

    /** A wait that is told of a loop on its second look. */
    private static final class LoopsOnSecondLook implements Terminal.Loops {

        private final AtomicInteger looks = new AtomicInteger();

        private int resets;

        @Override
        public void reset() {
            resets++;
        }

        @Override
        public Optional<String> check() {
            return looks.incrementAndGet() < 2 ? Optional.empty()
                    : Optional.of("In sokar-busy-busy the tool failure \"✘ Write\" scrolled by 3 times");
        }
    }

    private static Terminal showing(String shown) {
        return new Terminal(new ByteArrayInputStream(shown.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), () -> { });
    }

    @Test
    void stopsALongWaitAsSoonAsAnAgentRepeatsAFailure() {
        final LoopsOnSecondLook loops = new LoopsOnSecondLook();
        final Terminal terminal = showing("A message for you waits").watching(loops);
        final long began = System.nanoTime();

        assertThatThrownBy(() -> terminal.await("5555", Duration.ofSeconds(240)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Stopped waiting for \"5555\"")
                .hasMessageContaining("\"✘ Write\" scrolled by 3 times")
                .hasMessageContaining("A message for you waits");

        assertThat(Duration.ofNanos(System.nanoTime() - began)).as("of a 240-second wait")
                .isLessThan(Terminal.LOOK.multipliedBy(3));
        assertThat(loops.resets).as("the wait began by forgetting what came before").isEqualTo(1);
    }

    @Test
    void whatAWaitIsForEndsItBeforeAnyLoopIsLookedFor() throws IOException {
        final LoopsOnSecondLook loops = new LoopsOnSecondLook();

        assertThat(showing("5555").watching(loops).await("5555", Duration.ofSeconds(240))).contains("5555");
        assertThat(loops.looks).hasValue(0);
    }

    @Test
    void aFailureSaysNothingTheFilterHides() {
        final Terminal terminal = showing("").watching(new LoopsOnSecondLook())
                .showing(text -> text.replace("sokar-busy-busy", "***"));

        assertThatThrownBy(() -> terminal.await("5555", Duration.ofSeconds(240))).hasMessageNotContaining("sokar-busy-busy");
    }
}
