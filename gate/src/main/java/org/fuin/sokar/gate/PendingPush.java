package org.fuin.sokar.gate;

import java.time.Duration;
import java.time.Instant;

/**
 * A push waiting for review, and how long it has been waiting.
 *
 * @param name Incoming ref name, without the namespace prefix.
 * @param commit Commit the ref points at.
 * @param subject Subject line of that commit.
 * @param at When the commit was made.
 */
public record PendingPush(String name, String commit, String subject, Instant at) {

    /**
     * Returns how long this push has been waiting.
     * <p>
     * <strong>Review lag is the number that says whether the gate is working.</strong> A gate
     * whose queue is never read stops being a control and becomes a queue: the agent's work piles
     * up, the operator eventually approves in bulk without reading, and the review was theatre.
     * Showing the age makes that visible before it becomes a habit.
     *
     * @param now The current time.
     * @return Time since the push was made.
     */
    public Duration lag(Instant now) {
        return Duration.between(at, now);
    }

    /**
     * Returns the lag in a form an operator reads at a glance.
     *
     * @param now The current time.
     * @return Short human-readable duration, such as {@code 3d 4h}.
     */
    public String lagText(Instant now) {
        final Duration lag = lag(now);
        if (lag.isNegative()) {
            // A commit dated in the future: a wrong clock somewhere, and worth showing as odd
            // rather than as a huge negative number.
            return "in the future";
        }
        final long days = lag.toDays();
        final long hours = lag.toHoursPart();
        final long minutes = lag.toMinutesPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    /**
     * Tells whether this push has been waiting longer than a threshold.
     *
     * @param now The current time.
     * @param threshold How long is too long.
     * @return {@code true} if the lag exceeds the threshold.
     */
    public boolean isStale(Instant now, Duration threshold) {
        return lag(now).compareTo(threshold) > 0;
    }
}
