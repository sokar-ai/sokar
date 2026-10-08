package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Age}.
 */
class AgeTest {

    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");

    private static String at(String since) {
        return Age.compact(since, NOW);
    }

    @Test
    void countsInTheLargestUnitThatFits() {
        assertThat(at("2026-09-09T11:59:30Z")).isEqualTo("<1m");
        assertThat(at("2026-09-09T11:56:00Z")).isEqualTo("4m");
        assertThat(at("2026-09-09T10:00:00Z")).isEqualTo("2h");
        assertThat(at("2026-09-06T12:00:00Z")).isEqualTo("3d");
    }

    @Test
    void changesUnitOnlyOnceTheSmallerOneIsFull() {

        // The boundaries, because "59 minutes" showing as "0h" is how a column becomes noise.
        assertThat(at("2026-09-09T11:01:00Z")).isEqualTo("59m");
        assertThat(at("2026-09-09T11:00:00Z")).isEqualTo("1h");
        assertThat(at("2026-09-08T13:00:00Z")).isEqualTo("23h");
        assertThat(at("2026-09-08T12:00:00Z")).isEqualTo("1d");
    }

    @Test
    void saysNothingRatherThanSomethingWrong() {

        // A wrong age is worse than a missing one: nothing about it looks wrong.
        assertThat(at("")).isEmpty();
        assertThat(at("not a timestamp")).isEmpty();
        assertThat(Age.compact(null, NOW)).isEmpty();
    }

    @Test
    void rendersAGenuineOldTimestampRatherThanHidingIt() {

        // Go's zero time never reaches here - Podman.since() answers empty for anything at or
        // below zero - so an old instant that did arrive is a real one and is stated as it is.
        assertThat(at("2025-09-09T12:00:00Z")).isEqualTo("365d");
    }

    @Test
    void refusesAFutureTimestamp() {

        // Clock skew between the host and what the runtime recorded. "-3m" in a column of ages
        // is not information, it is a bug somebody has to explain.
        assertThat(at("2026-09-09T12:05:00Z")).isEmpty();
    }
}
