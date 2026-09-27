package org.fuin.sokar.agent.api;

import org.jspecify.annotations.Nullable;

/**
 * Turns one line of an agent's output into something worth showing an operator.
 * <p>
 * <strong>Deliberately an interface, not an enumeration.</strong> An enum would need a constant per
 * agent that has its own format, which would put agent names into this module - the exact coupling
 * the whole structure exists to prevent. An agent with an unusual format implements this in its own
 * module and overrides {@link Agent#logFormatter()}.
 */
@FunctionalInterface
public interface LogFormatter {

    /**
     * Formats one line.
     *
     * @param line Raw output line.
     * @return What to show, or {@code null} to show nothing for this line.
     */
    @Nullable String format(String line);

    /**
     * Returns a formatter that passes every line through unchanged.
     *
     * @return Plain formatter.
     */
    static LogFormatter plain() {
        return line -> line;
    }
}
