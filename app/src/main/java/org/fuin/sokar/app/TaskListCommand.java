package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.ContainerSummary;
import org.fuin.sokar.wire.Sidecar;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists the tasks on this machine.
 * <p>
 * The container runtime knows the containers; only Sokar knows which project and security class
 * each belongs to, which is what an operator with several tasks running actually needs to tell
 * them apart. Both come from the task's own state directory rather than from the container name,
 * because a project name may contain the separator the name is built with.
 */
@Command(name = "list",
        mixinStandardHelpOptions = true,
        description = "Lists the tasks on this machine.")
public class TaskListCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Reads what Sokar recorded for a container, or {@code null} when nothing is left.
     *
     * @param container Container name.
     * @return The sidecar, or {@code null}.
     */
    private Sidecar sidecarOf(String container) {
        try {
            final Path file = context.paths().containerState(container).resolve("sidecar.json");
            return Files.isRegularFile(file) ? Sidecar.readFrom(file) : null;
        } catch (java.io.IOException | RuntimeException ex) {
            // A task whose sidecar cannot be read is still worth listing, just with less detail.
            return null;
        }
    }

    /**
     * Counts the helpers a task still has running.
     *
     * @param container Container name.
     * @return How many recorded pids are alive.
     */
    private long helpersOf(String container) {
        final Path state = context.paths().containerState(container);
        if (!Files.isDirectory(state)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> files = Files.list(state)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".pid"))
                    .filter(TaskLifecycle::alive)
                    .count();
        } catch (java.io.IOException ex) {
            return 0;
        }
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final List<ContainerSummary> tasks = context.podman().sokarTasks();

        if (tasks.isEmpty()) {
            out.println("No tasks.");
            out.flush();
            return 0;
        }

        out.printf("%-38s %-14s %-9s %-18s %s%n",
                "NAME", "PROJECT", "CLASS", "STATE", "HELPERS");
        for (final ContainerSummary task : tasks) {
            final Sidecar sidecar = sidecarOf(task.name());
            out.printf("%-38s %-14s %-9s %-18s %s%n",
                    task.name(),
                    sidecar == null ? "-" : sidecar.project(),
                    sidecar == null ? "-" : sidecar.securityClass(),
                    state(task.state()),
                    helpersOf(task.name()));
        }
        out.flush();
        return 0;
    }

    /**
     * Returns the runtime's state, short enough to stay in its column.
     * <p>
     * The runtime writes a phrase, and its longest - {@code Exited (143) Less than a second ago} -
     * is twice the width of the column, so it ran into the helper count and the row stopped being
     * a table. The age is what gets dropped: this is a list of what exists and whether it is up,
     * and {@code task list} is how an operator finds the name to stop or resume.
     *
     * @param state What the runtime called it.
     * @return The state, at most as wide as its column.
     */
    private static String state(String state) {
        // 'Exited (143) Less than a second ago' and 'Exited (0) 2 minutes ago' both carry an age
        // this table has no room for, and the exit code is the part worth keeping: it is how a
        // task that was stopped is told apart from one that died.
        final int code = state.startsWith("Exited (") ? state.indexOf(')') : -1;
        final String shortened = code > 0 ? state.substring(0, code + 1) : state;
        return shortened.length() <= 18 ? shortened : shortened.substring(0, 17) + "…";
    }
}
