package org.fuin.sokar.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.runtime.ContainerSummary;
import org.fuin.sokar.wire.Sidecar;
import org.jspecify.annotations.Nullable;

/**
 * What tasks exist on this machine, as data rather than as a printed table.
 * <p>
 * The CLI renders this and the daemon serialises it, so that "what tasks are there" is answered in
 * one place. The interface and the CLI have to reach identical behaviour through the same calls;
 * two implementations of the same question are how a feature comes to exist in one and not the
 * other, and how they come to disagree about something an operator is reading to decide what to
 * stop.
 * <p>
 * The container runtime knows the containers; only Sokar knows which project and security class
 * each belongs to. Both come from the task's own state directory rather than from the container
 * name, because a project name may contain the separator that name is built with.
 */
public final class TaskInventory {

    /**
     * One task, as much as this machine can say about it.
     *
     * @param name Container name, which is what every other command takes.
     * @param project Project the task belongs to, or {@code null} when nothing recorded it.
     * @param securityClass The project's class, or {@code null} when nothing recorded it.
     * @param state The runtime's own words, such as {@code Up 4 minutes}.
     * @param running Whether the runtime says it is up.
     * @param helpers How many recorded helper processes are alive.
     */
    public record Task(String name, @Nullable String project, @Nullable String securityClass,
            String state, boolean running, long helpers) {

        /**
         * Returns this task as plain values, for a caller that has to put it on a wire.
         *
         * @return The task, with {@code null} replaced by an empty string so the map is
         *         representable in every encoding a client might use.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("project", project == null ? "" : project);
            map.put("securityClass", securityClass == null ? "" : securityClass);
            map.put("state", state);
            map.put("running", running);
            map.put("helpers", helpers);
            return map;
        }
    }

    private final SokarContext context;

    /**
     * Constructor with the context to read from.
     *
     * @param context Where podman and the paths come from.
     */
    public TaskInventory(SokarContext context) {
        this.context = context;
    }

    /**
     * Returns every task on this machine, running or stopped.
     *
     * @return Tasks, in the order the runtime lists them.
     */
    public List<Task> tasks() {
        return context.podman().sokarTasks().stream().map(this::describe).toList();
    }

    private Task describe(ContainerSummary summary) {
        final Sidecar sidecar = sidecarOf(summary.name());
        return new Task(summary.name(),
                sidecar == null ? null : sidecar.project(),
                sidecar == null ? null : sidecar.securityClass(),
                summary.state(), summary.running(), helpersOf(summary.name()));
    }

    /**
     * Reads what Sokar recorded for a container, or {@code null} when nothing is left.
     */
    private @Nullable Sidecar sidecarOf(String container) {
        try {
            final Path file = context.paths().containerState(container).resolve("sidecar.json");
            return Files.isRegularFile(file) ? Sidecar.readFrom(file) : null;
        } catch (java.io.IOException | RuntimeException ex) {
            // A task whose sidecar cannot be read is still worth listing, just with less detail.
            return null;
        }
    }

    /**
     * Counts the helper processes a task's state directory records as alive.
     */
    private long helpersOf(String container) {
        final Path state = context.paths().containerState(container);
        if (!Files.isDirectory(state)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(state)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".pid"))
                    .filter(TaskLifecycle::alive)
                    .count();
        } catch (java.io.IOException ex) {
            return 0;
        }
    }
}
