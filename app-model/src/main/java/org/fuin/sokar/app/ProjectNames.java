package org.fuin.sokar.app;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Says whose task a container is.
 * <p>
 * Not by the name's beginning alone: project and task names both take hyphens, so {@code sokar-web-app-x} is task
 * {@code app-x} of {@code web} or task {@code x} of {@code web-app}. Taken by the first project that fitted, a task of
 * one was the other's in messaging - its peers, its conversation, its tasks as vouched siblings. The container's
 * project label decides; a name alone only when it can mean one project.
 */
final class ProjectNames {

    private ProjectNames() {
    }

    /**
     * Returns the project of a container.
     *
     * @param container The container's name.
     * @param labels Each container's project label, as the runtime reports it.
     * @param projects The projects this machine has.
     * @return The project, or {@code null} when nothing says, or more than one could be meant.
     */
    static @Nullable String of(String container, Map<String, String> labels, List<String> projects) {
        final String labelled = labels.get(container);
        if (labelled != null && !labelled.isBlank()) {
            return labelled;
        }
        final List<String> fitting = projects.stream().filter(name -> container.startsWith("sokar-" + name + "-"))
                .toList();
        return fitting.size() == 1 ? fitting.getFirst() : null;
    }

    /**
     * Returns each Sokar container's project label, as the runtime reports it now.
     *
     * @param context The machine.
     * @return Container to project.
     */
    static Map<String, String> labels(SokarContext context) {
        final Map<String, String> labels = new java.util.HashMap<>();
        for (final org.fuin.sokar.runtime.ContainerSummary task : context.podman().sokarTasks()) {
            if (task.project() != null) {
                labels.put(task.name(), task.project());
            }
        }
        return labels;
    }
}
