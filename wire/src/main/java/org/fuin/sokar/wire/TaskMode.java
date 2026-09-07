package org.fuin.sokar.wire;

import java.util.Locale;

/**
 * How a person is meant to be involved in a task.
 * <p>
 * Three different things to walk up to, and nothing said which until this existed: a task somebody
 * is typing in, one they are working through the agent's own session, and one left to run on its
 * own. An interface deciding what to offer - attach, watch, or nothing - is deciding between
 * these, and guessing from whether a prompt was given would be the same rule in a second place.
 * <p>
 * In {@code sokar-wire} because the launcher writes it, the daemon serves it and the interface
 * reads it, and none of those may own the vocabulary alone.
 */
public enum TaskMode {

    /** A terminal in the container, driven by hand. */
    SHELL,

    /** The agent's own session, attached, with a person working through it. */
    AGENT,

    /** Started with a prompt and left to run. Nobody is expected to be watching. */
    UNATTENDED;

    /**
     * Returns the value as it is written down and put on the wire.
     *
     * @return Lower-case name.
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a value, defaulting rather than failing.
     * <p>
     * A task recorded by an older Sokar has no mode at all, and one recorded by a newer one may
     * have a value this build does not know. Neither is a reason to refuse to describe a task
     * that is running perfectly well.
     *
     * @param value Value as written, may be {@code null} or empty.
     * @param fallback What to answer when it is neither.
     * @return The mode.
     */
    public static TaskMode parse(String value, TaskMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        for (final TaskMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.strip())) {
                return mode;
            }
        }
        return fallback;
    }
}
