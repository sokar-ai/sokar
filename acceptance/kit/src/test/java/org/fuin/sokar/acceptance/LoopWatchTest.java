package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LoopWatch}, on histories as a task's session shows them.
 */
class LoopWatchTest {

    private static final String TASK = "sokar-busy-busy";

    /** The header of one failed try, as the looping agent drew it. */
    private static final String FAILED = "╭─── ✘ Write: /run/sokar/mail/outbox/tmp/reply-1234.json ───╮";

    private static String history(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static String times(int count, String line) {
        return (line + "\n").repeat(count);
    }

    @Test
    void aToolFailureThatScrolledByThreeTimesIsALoop() {
        final LoopWatch watch = new LoopWatch("✘ Write", LoopWatch.DEFAULT_REPEATS);
        assertThat(watch.check(Map.of(TASK, history("› What is 1234 plus 4321?")))).as("the beginning").isEmpty();

        final var loop = watch.check(Map.of(TASK, history("› What is 1234 plus 4321?", FAILED, "╰───╯", FAILED,
                "╰───╯", FAILED)));

        assertThat(loop).hasValueSatisfying(said -> assertThat(said).contains(TASK, "\"✘ Write\"", "3 times", FAILED));
    }

    @Test
    void twoFailuresAreAnAgentTryingAgain() {
        final LoopWatch watch = new LoopWatch("✘ Write", LoopWatch.DEFAULT_REPEATS);
        watch.check(Map.of(TASK, ""));

        assertThat(watch.check(Map.of(TASK, history(FAILED, FAILED)))).isEmpty();
    }

    @Test
    void whatScrolledByBeforeTheStepIsTheAgentsPast() {
        final LoopWatch watch = new LoopWatch("✘ Write", LoopWatch.DEFAULT_REPEATS);
        watch.check(Map.of(TASK, times(3, FAILED)));

        assertThat(watch.check(Map.of(TASK, times(5, FAILED)))).as("two since the step began").isEmpty();
        watch.reset();
        watch.check(Map.of(TASK, times(5, FAILED)));
        assertThat(watch.check(Map.of(TASK, times(7, FAILED)))).as("a step begun after reset").isEmpty();
    }

    @Test
    void aTaskStartedDuringTheStepCountsFromNothing() {
        final LoopWatch watch = new LoopWatch("✘ Write", LoopWatch.DEFAULT_REPEATS);
        watch.check(Map.of());

        assertThat(watch.check(Map.of(TASK, times(3, FAILED)))).isPresent();
    }

    @Test
    void withoutAMarkerOneLineScrollingByMoreThanTheLimitAllowsIsALoop() {
        final LoopWatch watch = new LoopWatch(null, 25);
        watch.check(Map.of(TASK, ""));

        assertThat(watch.check(Map.of(TASK, times(25, "Error: content is required")))).as("25").isEmpty();
        assertThat(watch.check(Map.of(TASK, times(26, "Error: content is required"))))
                .hasValueSatisfying(said -> assertThat(said).contains(TASK, "26 times", "25", LoopWatch.REPEATS,
                        "Error: content is required"));
    }

    @Test
    void theEdgeOfEveryBoxIsNotALoop() {
        final LoopWatch watch = new LoopWatch(null, 25);
        watch.check(Map.of(TASK, ""));

        assertThat(watch.check(Map.of(TASK, times(100, "╰──────────────────╯") + times(100, "")))).isEmpty();
    }

    @Test
    void readsEachTasksHistoryFromWhatTheCommandPrinted() {
        final String printed = LoopWatch.SEPARATOR + "sokar-a-one\nfirst\nsecond\n" + LoopWatch.SEPARATOR
                + "sokar-b-two\n" + LoopWatch.SEPARATOR + "sokar-c-three\nthird\n";

        assertThat(LoopWatch.histories(printed)).containsExactly(Map.entry("sokar-a-one", "first\nsecond\n"),
                Map.entry("sokar-b-two", ""), Map.entry("sokar-c-three", "third\n"));
        assertThat(LoopWatch.histories("")).isEmpty();
    }

    @Test
    void refusesALimitThatIsNoNumber() {
        System.setProperty(LoopWatch.REPEATS, "many");
        try {
            assertThatThrownBy(LoopWatch::repeats).hasMessageContaining(LoopWatch.REPEATS).hasMessageContaining("many");
        } finally {
            System.clearProperty(LoopWatch.REPEATS);
        }
        assertThat(LoopWatch.repeats()).isEqualTo(LoopWatch.DEFAULT_REPEATS);
    }
}
