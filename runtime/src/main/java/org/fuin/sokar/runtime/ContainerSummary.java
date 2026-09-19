package org.fuin.sokar.runtime;

/**
 * What the container runtime reports about one task container.
 *
 * @param name Container name.
 * @param state Runtime's own words for the state, such as {@code Up 4 minutes} or {@code Created}.
 * @param since When the current state began, ISO-8601, or empty.
 * @param project Project from the container's label, or {@code null} if it carries none.
 * @param securityClass Security class from the container's label, or {@code null}.
 */
public record ContainerSummary(String name, String state, String since,
        @org.jspecify.annotations.Nullable String project,
        @org.jspecify.annotations.Nullable String securityClass,
        @org.jspecify.annotations.Nullable String repository,
        @org.jspecify.annotations.Nullable String commit) {

    /**
     * Constructor for a summary taken before the commit was labelled.
     *
     * @param name Container name.
     * @param state The runtime's own words.
     * @param since When it entered that state.
     * @param project Project name, or {@code null}.
     * @param securityClass Security class, or {@code null}.
     * @param repository Repository name, or {@code null}.
     */
    public ContainerSummary(String name, String state, String since,
            @org.jspecify.annotations.Nullable String project,
            @org.jspecify.annotations.Nullable String securityClass,
            @org.jspecify.annotations.Nullable String repository) {
        this(name, state, since, project, securityClass, repository, null);
    }

    /**
     * Constructor for a summary taken before the repository was labelled.
     *
     * @param name Container name.
     * @param state The runtime's own words.
     * @param since When it entered that state.
     * @param project Project name, or {@code null}.
     * @param securityClass Security class, or {@code null}.
     */
    public ContainerSummary(String name, String state, String since,
            @org.jspecify.annotations.Nullable String project,
            @org.jspecify.annotations.Nullable String securityClass) {
        this(name, state, since, project, securityClass, null, null);
    }

    /**
     * Constructor for a summary whose timestamps the runtime did not give.
     *
     * @param name Container name.
     * @param state The runtime's own words.
     */
    public ContainerSummary(String name, String state) {
        this(name, state, "", null, null);
    }

    /**
     * Constructor for a summary without labels, as a container created before they existed.
     *
     * @param name Container name.
     * @param state The runtime's own words.
     * @param since When the state began.
     */
    public ContainerSummary(String name, String state, String since) {
        this(name, state, since, null, null);
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
