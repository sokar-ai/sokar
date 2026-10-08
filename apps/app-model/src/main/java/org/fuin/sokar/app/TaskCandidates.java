package org.fuin.sokar.app;

import java.util.List;
import org.fuin.sokar.runtime.ContainerSummary;

/**
 * The task names a command could have been given.
 * <p>
 * Read through {@code sokarTasks()}, which filters on {@link
 * org.fuin.sokar.runtime.ContainerName#isTask} - so the throwaway containers {@code vault login}
 * creates are not offered. Building this on the {@code sokar-} prefix instead would put them back,
 * which is the defect that put them in {@code task list}.
 */
final class TaskCandidates {

    private TaskCandidates() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the tasks that are up.
     *
     * @param context Where to ask.
     * @return Names.
     */
    static List<String> running(SokarContext context) {
        return context.podman().sokarTasks().stream()
                .filter(ContainerSummary::running).map(ContainerSummary::name).sorted().toList();
    }

    /**
     * Returns the tasks that exist and are not up.
     *
     * @param context Where to ask.
     * @return Names.
     */
    static List<String> stopped(SokarContext context) {
        return context.podman().sokarTasks().stream()
                .filter(task -> !task.running()).map(ContainerSummary::name).sorted().toList();
    }

    /**
     * Returns every task, running or not.
     *
     * @param context Where to ask.
     * @return Names.
     */
    static List<String> all(SokarContext context) {
        return context.podman().sokarTasks().stream()
                .map(ContainerSummary::name).sorted().toList();
    }
}
