package org.fuin.sokar.runtime;

/**
 * What the container runtime reports about one task container.
 *
 * @param name Container name.
 * @param state Runtime's own words for the state, such as {@code Up 4 minutes} or {@code Created}.
 */
public record ContainerSummary(String name, String state) {

    /**
     * Tells whether the container is running.
     * <p>
     * A container that was created and never started reports {@code Created}, which matters here:
     * that is the state a refused ruleset leaves behind, and it is not a running task.
     *
     * @return {@code true} if the runtime says it is up.
     */
    public boolean running() {
        return state.startsWith("Up");
    }
}
