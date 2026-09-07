package org.fuin.sokar.runtime;

/**
 * What the container runtime reports about one task container.
 *
 * @param name Container name.
 * @param state Runtime's own words for the state, such as {@code Up 4 minutes} or {@code Created}.
 */
public record ContainerSummary(String name, String state, String since) {

    /**
     * Constructor for a summary whose timestamps the runtime did not give.
     *
     * @param name Container name.
     * @param state The runtime's own words.
     */
    public ContainerSummary(String name, String state) {
        this(name, state, "");
    }

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

    /**
     * Returns when the current state began.
     * <p>
     * A timestamp rather than the runtime's own words. {@code state} is a phrase for a person -
     * "Up 4 minutes", "Exited (143) 2 seconds ago" - and nothing may parse it, which leaves "how
     * long has it been like this" answerable only by arithmetic on this.
     *
     * @return ISO-8601 instant, or empty when the runtime could not say.
     */
    public String since() {
        return since;
    }
}
