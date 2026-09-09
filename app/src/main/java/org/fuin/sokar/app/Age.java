package org.fuin.sokar.app;

import java.time.Duration;
import java.time.Instant;

/**
 * How long ago something happened, short enough for a column.
 * <p>
 * The runtime already says this in words - "Up 4 minutes", "Exited (143) 2 seconds ago" - and
 * those words are for a person, not for a parser, which is why what is kept is an instant and the
 * rendering happens here. It is also why the phrase is not simply printed: it is twice the width
 * of the column it would go in, and it used to run into the count beside it.
 */
final class Age {

    private Age() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a compact age, or an empty string when the runtime did not say.
     * <p>
     * <strong>An unusable timestamp answers nothing rather than something.</strong> Anything that
     * cannot be parsed, or lies in the future, is left blank - a wrong age is worse than a missing
     * one, because nothing about it looks wrong.
     * <p>
     * Go's zero time - what a container created and never started reports, which podman renders
     * as "292 years ago" - never reaches here: {@code Podman.since()} treats anything at or below
     * zero as "the runtime did not say" and hands over an empty string. A very old but genuine
     * timestamp is rendered as the days it really is.
     *
     * @param since When the current state began, ISO-8601, or empty.
     * @param now The moment to measure against.
     * @return Something like {@code 4m}, {@code 2h}, {@code 3d}, or empty.
     */
    static String compact(String since, Instant now) {
        if (since == null || since.isBlank()) {
            return "";
        }
        final Instant began;
        try {
            began = Instant.parse(since);
        } catch (java.time.format.DateTimeParseException ex) {
            return "";
        }
        final Duration elapsed = Duration.between(began, now);
        if (elapsed.isNegative()) {
            // Clock skew, or the zero time rendered as something parseable. Either way this
            // cannot be stated.
            return "";
        }
        if (elapsed.toMinutes() < 1) {
            return "<1m";
        }
        if (elapsed.toHours() < 1) {
            return elapsed.toMinutes() + "m";
        }
        if (elapsed.toDays() < 1) {
            return elapsed.toHours() + "h";
        }
        return elapsed.toDays() + "d";
    }
}
