package org.fuin.sokar.core.project;

import org.jspecify.annotations.Nullable;

/**
 * What one task container may consume.
 * <p>
 * An agent in YOLO mode runs commands nobody reviewed, so a runaway build is a normal outcome
 * rather than an attack. Memory is the one that takes the host down; podman already caps processes
 * at 2048, which is pinned here so the protection does not depend on a distribution default.
 *
 * @param memory Value for {@code --memory}, for example {@code 8g}, or {@code null} for no limit.
 * @param cpus Value for {@code --cpus}, for example {@code 2.0}, or {@code null} for no limit.
 * @param pids Value for {@code --pids-limit}.
 */
public record Limits(@Nullable String memory, @Nullable String cpus, int pids) {

    /** Memory a task gets unless the project says otherwise. */
    public static final String DEFAULT_MEMORY = "8g";

    /** Processes a task gets unless the project says otherwise, matching podman's own default. */
    public static final int DEFAULT_PIDS = 2048;

    /**
     * Returns the limits a project gets when it declares none.
     *
     * @return Defaults.
     */
    public static Limits defaults() {
        return new Limits(DEFAULT_MEMORY, null, DEFAULT_PIDS);
    }

    /**
     * Constructor.
     *
     * @param memory Memory limit, or {@code null}.
     * @param cpus CPU limit, or {@code null}.
     * @param pids Process limit.
     */
    public Limits {
        if (pids < 1) {
            throw new ProjectException("A process limit of " + pids + " leaves nothing to run");
        }
    }
}
