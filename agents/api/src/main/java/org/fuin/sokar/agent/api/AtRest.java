package org.fuin.sokar.agent.api;

import java.util.List;

/**
 * What an agent's screen shows when it has finished a turn and waits for the next, at its prompt.
 * <p>
 * So a message that names its task can wake it, and never while it works or asks a person something: the line Sokar
 * types then would interrupt it, or answer the question (Agent Smith, 2026-10-04). Neither {@link ReadyMarker}, which
 * says it reached work after its start, nor {@link Waiting}, which says a question is open, tells that. An agent that
 * declares nothing here is never typed into.
 *
 * @param shows What must all be on the screen.
 * @param lacks What must none be on it.
 */
public record AtRest(List<String> shows, List<String> lacks) {

    /**
     * Constructor with checks.
     *
     * @param shows What must all be on the screen.
     * @param lacks What must none be on it.
     */
    public AtRest {
        shows = List.copyOf(shows);
        lacks = List.copyOf(lacks);
        if (shows.isEmpty() && lacks.isEmpty()) {
            throw new AgentException("'session.at_rest' says nothing: give what its screen shows or lacks at rest");
        }
    }

    /**
     * Says whether a screen shows the agent at rest.
     *
     * @param screen The screen's text, as drawn.
     * @return {@code true} when it shows all it must and none of what it must not.
     */
    public boolean matches(String screen) {
        return shows.stream().allMatch(screen::contains) && lacks.stream().noneMatch(screen::contains);
    }
}
