package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for how often {@link UpstreamWatch} looks, and whether it looks at all.
 * <p>
 * A daemon that reaches the network on its own is something an operator is entitled to refuse - on
 * a metered connection, or on a machine meant to be quiet - so the switch is part of the feature
 * rather than an afterthought.
 */
class UpstreamWatchTest {

    private static java.util.function.UnaryOperator<String> saying(String value) {
        return name -> Map.of(UpstreamWatch.INTERVAL_VARIABLE, value).get(name);
    }

    @Test
    void looksEveryQuarterHourWhenNothingSaysOtherwise() {

        assertThat(UpstreamWatch.interval(name -> null)).isEqualTo(UpstreamWatch.DEFAULT_INTERVAL);
    }

    @Test
    void takesTheIntervalAnOperatorAsksFor() {

        assertThat(UpstreamWatch.interval(saying("60"))).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void zeroTurnsTheMeasuringOffEntirely() {

        // The only way to stop a daemon touching the network by itself, so it has to be exact:
        // anything that merely made it rare would be a different promise.
        assertThat(UpstreamWatch.interval(saying("0"))).isZero();
    }

    @Test
    void aTurnedOffWatchStartsNoThread() {

        final UpstreamWatch watch = new UpstreamWatch(null, Duration.ZERO);
        watch.start();

        assertThat(Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)).noneMatch("sokar-upstream"::equals);
    }

    @Test
    void nonsenseFallsBackToTheDefaultRatherThanTurningItOff() {

        // A misspelt variable that silently disabled this would be found weeks later as "the
        // number never updates", which is the worst way to learn about a typo.
        assertThat(UpstreamWatch.interval(saying("every fifteen minutes")))
                .isEqualTo(UpstreamWatch.DEFAULT_INTERVAL);
        assertThat(UpstreamWatch.interval(saying(""))).isEqualTo(UpstreamWatch.DEFAULT_INTERVAL);
    }
}
