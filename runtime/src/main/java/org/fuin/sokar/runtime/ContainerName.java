package org.fuin.sokar.runtime;

import org.fuin.sokar.core.project.Project;

/**
 * Names of the objects Sokar creates in podman.
 * <p>
 * Every name carries the {@code sokar-} prefix so that an operator looking at {@code podman ps}
 * can tell what belongs to Sokar, and so that a cleanup can never match somebody else's container.
 */
public final class ContainerName {

    /** Prefix on every container Sokar creates. */
    public static final String PREFIX = "sokar-";

    private ContainerName() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the container name for one task run.
     *
     * @param project The project.
     * @param task Task name.
     * @param runId Identifier unique within the task.
     * @return Container name.
     */
    public static String of(Project project, String task, String runId) {
        return PREFIX + project.name() + "-" + task + "-" + runId;
    }

    /**
     * Tells whether a name belongs to Sokar.
     *
     * @param name Container name.
     * @return {@code true} if Sokar created it.
     */
    public static boolean isSokar(String name) {
        return name.startsWith(PREFIX);
    }
}
