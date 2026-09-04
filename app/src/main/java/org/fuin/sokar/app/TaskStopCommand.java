package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.ContainerName;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Stops a task and everything it started.
 * <p>
 * <strong>The container is stopped, not removed.</strong> Its filesystem is the task's workspace,
 * so removing it would throw away the agent's work and make {@code sokar task resume} impossible.
 * Removal is a separate decision, asked for with {@code --purge}.
 * <p>
 * Stopping the container is not enough on its own either: the credential proxy, the git gate and
 * the clearance watcher run on the host. The poststop hook reaps what a container that ran
 * recorded; anything it misses is stopped here, and every helper is checked afterwards rather
 * than assumed to be gone.
 */
@Command(name = "stop",
        mixinStandardHelpOptions = true,
        description = "Stops a task, its container and its helpers.")
public class TaskStopCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "TASK",
            description = "Container name, as shown by 'sokar task list'.")
    private String container;

    @Option(names = "--purge",
            description = "Also removes the container and the task's state directory, so the"
                    + " workspace and the logs are gone and the task cannot be resumed.")
    private boolean purge;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        if (!ContainerName.isSokar(container)) {
            // Refused rather than guessed at: this command stops processes and removes a
            // container, and a name Sokar did not create belongs to somebody else.
            err.println("sokar: '" + container + "' is not a task Sokar created");
            err.flush();
            return 64;
        }

        final Path state = context.paths().containerState(container);
        final boolean known = context.podman().sokarTasks().stream()
                .anyMatch(task -> task.name().equals(container)) || Files.isDirectory(state);

        // Counted first: stopping the container fires the poststop hook, which reaps the helpers
        // and deletes their pid files, leaving nothing to count afterwards.
        final java.util.List<ProcessHandle> helpers = TaskLifecycle.running(state);

        // The container may be running, stopped already, or gone. All three are fine, and a task
        // that has already gone is not an error: an operator stopping something twice wants it
        // stopped, not a complaint.
        if (purge) {
            context.podman().remove(container);
        } else {
            context.podman().stop(container);
        }
        TaskLifecycle.stopHelpers(state);

        if (!known) {
            out.println("No task " + container + "; nothing to stop.");
            out.flush();
            return 0;
        }

        // Verified rather than assumed: a helper that ignored the signal is the case worth
        // reporting, because it still holds its socket and the next run trips over it. Waited for
        // first - termination is asynchronous, so checking straight away reports live processes
        // that are already on their way out.
        for (final ProcessHandle helper : helpers) {
            try {
                helper.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException ex) {
                // Still running, which the check below reports.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        final java.util.List<ProcessHandle> surviving =
                helpers.stream().filter(ProcessHandle::isAlive).toList();

        out.println("stopped   " + container + (purge ? " and removed" : ""));
        out.println("helpers   " + (helpers.size() - surviving.size())
                + " of " + helpers.size() + " stopped");
        for (final ProcessHandle alive : surviving) {
            out.println("          still running: " + alive.pid() + " "
                    + alive.info().command().orElse("?"));
        }

        if (purge) {
            out.println("removed   " + state);
            deleteTree(state);
        } else if (Files.isDirectory(state)) {
            out.println("resume    sokar task resume " + container);
            out.println("logs      " + state);
        }
        out.flush();
        return surviving.isEmpty() ? 0 : 70;
    }

    private void deleteTree(Path directory) {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ex) {
                    // Best effort: what is left is a directory the runtime clears at logout.
                }
            });
        } catch (java.io.IOException ex) {
            // Same.
        }
    }
}
