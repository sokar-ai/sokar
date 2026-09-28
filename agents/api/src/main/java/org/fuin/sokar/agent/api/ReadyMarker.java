package org.fuin.sokar.agent.api;

import org.jspecify.annotations.Nullable;

/**
 * What an agent shows once it has reached work, attached, and asks nothing.
 * <p>
 * <strong>Declared by the agent, because only the agent knows it.</strong> Every agent looks different
 * at its prompt, and a check that knew one of them would prove what that one does and be silent about
 * the rest. So the agent names a piece of text, and a check waits for it without typing anything:
 * anything that asks first - a trust question, a login, a setup wizard - blocks it, and the check
 * fails and shows the screen. That is "reached work, and nothing came first", which still holds the
 * day a release adds a question nobody has seen yet, where "the dialogs we know about are absent"
 * would pass.
 *
 * @param text Text on the screen that means the agent is at work - one line, as the agent prints it.
 * @param withinSeconds How long reaching it may take, or {@code null} for the checker's default.
 */
public record ReadyMarker(String text, @Nullable Integer withinSeconds) {

    /** The longest bound an agent may declare: an agent slower than this to start is not at work. */
    public static final int MOST_SECONDS = 600;

    /**
     * Constructor with validation.
     *
     * @param text The text.
     * @param withinSeconds The bound, or {@code null}.
     */
    public ReadyMarker {
        if (text.isBlank() || text.contains("\n") || text.contains("\r")) {
            throw new AgentException("A ready marker is one line of text the agent shows, not '" + text + "'");
        }
        if (withinSeconds != null && (withinSeconds < 1 || withinSeconds > MOST_SECONDS)) {
            throw new AgentException("A ready marker's bound is 1 to " + MOST_SECONDS + " seconds, not "
                    + withinSeconds);
        }
    }
}
