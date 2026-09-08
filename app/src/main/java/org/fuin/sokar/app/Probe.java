package org.fuin.sokar.app;

import org.jspecify.annotations.Nullable;

/**
 * One thing outside Sokar that a task depends on, and what this machine has of it.
 * <p>
 * <strong>A probe that is not healthy carries the next action, and the constructor refuses one
 * that does not.</strong> Every dependency here fails far from its cause - a missing capability in
 * a resolver, a policy that blocks a mount, a binary that is not installed - so the report is only
 * worth anything if each line ends with the one thing to do about it. Enforced rather than
 * remembered, because the line somebody forgets is the line an operator is reading at their worst
 * moment.
 *
 * @param name What is being reported, as an operator would name it.
 * @param state What this machine has.
 * @param detail What was found, in one line.
 * @param action The single next action, or {@code null} only when the state is {@link State#OK}.
 */
public record Probe(String name, State state, String detail,
        @Nullable String action) {

    /** What a probe found. */
    public enum State {

        /** Present and usable. */
        OK,

        /** Works, but not as well as it should, and the difference is worth knowing. */
        DEGRADED,

        /** Not there, or there and unusable. Something will fail because of it. */
        MISSING,

        /**
         * The probe could not answer.
         * <p>
         * Reported as its own state rather than as the good case: every dependency here is
         * something whose absence is invisible until a task behaves strangely, and a probe that
         * guesses well is indistinguishable from one that works.
         */
        UNKNOWN
    }

    /**
     * Constructor, refusing a failure with nothing to do about it.
     *
     * @param name What is being reported.
     * @param state What this machine has.
     * @param detail What was found.
     * @param action The next action, required unless the state is {@link State#OK}.
     */
    public Probe {
        if (state != State.OK && (action == null || action.isBlank())) {
            throw new IllegalArgumentException(
                    "The probe '" + name + "' is " + state + " and names no next action");
        }
    }

    /**
     * Returns a probe that found what it was looking for.
     *
     * @param name What is being reported.
     * @param detail What was found.
     * @return The probe.
     */
    static Probe ok(String name, String detail) {
        return new Probe(name, State.OK, detail, null);
    }

    /**
     * Returns a probe for something that works less well than it should.
     *
     * @param name What is being reported.
     * @param detail What was found.
     * @param action The next action.
     * @return The probe.
     */
    static Probe degraded(String name, String detail, String action) {
        return new Probe(name, State.DEGRADED, detail, action);
    }

    /**
     * Returns a probe for something that is not there.
     *
     * @param name What is being reported.
     * @param detail What was found.
     * @param action The next action.
     * @return The probe.
     */
    static Probe missing(String name, String detail, String action) {
        return new Probe(name, State.MISSING, detail, action);
    }

    /**
     * Returns a probe that could not answer.
     *
     * @param name What is being reported.
     * @param detail Why it could not answer.
     * @param action The next action.
     * @return The probe.
     */
    static Probe unknown(String name, String detail, String action) {
        return new Probe(name, State.UNKNOWN, detail, action);
    }

    /**
     * Tells whether this needs no attention.
     *
     * @return {@code true} when the state is {@link State#OK}.
     */
    boolean healthy() {
        return state == State.OK;
    }
}
