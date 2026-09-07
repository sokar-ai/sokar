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

    @Option(names = "--rescue",
            description = "Pushes work the agent never handed back to the gate first, under its"
                    + " own ref, so removing the task does not destroy it.")
    private boolean rescue;

    @Option(names = "--force",
            description = "Removes the task even though work would be lost with it.")
    private boolean force;

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
        final java.util.Optional<org.fuin.sokar.runtime.ContainerSummary> summary =
                context.podman().sokarTasks().stream()
                        .filter(task -> task.name().equals(container)).findFirst();
        final boolean known = summary.isPresent() || Files.isDirectory(state);
        final boolean running = summary
                .map(org.fuin.sokar.runtime.ContainerSummary::running).orElse(false);

        // Asked while the container and the gate are both still up: afterwards there is nothing
        // to ask, and nowhere to push what the answer finds. A task that is already stopped is
        // past that point, so what it left in its note is all there is.
        final java.util.Optional<String> noted = running
                ? java.util.Optional.empty() : UnhandedWork.note(state);
        final String work = running ? unhandedWork(container)
                : noted.filter(phrase -> !phrase.isEmpty()).orElse(null);
        if (work != null) {
            out.println("work      " + work + " that never reached the gate");
        }
        if (purge && summary.isPresent() && !running && work == null && noted.isEmpty()
                && !force) {
            // The container is there and stopped, nothing can see inside it, and nothing wrote
            // down what it held - stopped by something other than Sokar, or its runtime directory
            // went with a logout. Removing it would be the silent destruction this refuses. A
            // task that is gone entirely is a different case and still not an error: there is
            // nothing left to lose.
            err.println("sokar: refusing to remove " + container + ": it is stopped and nothing"
                    + " recorded what it holds");
            err.println("       resume it with 'sokar task resume " + container + "' to see, or"
                    + " --force to discard it unseen.");
            err.flush();
            return 65;
        }
        if (work != null && purge && !rescue && !force) {
            err.println("sokar: refusing to remove " + container + ": it holds " + work);
            err.println("       it exists nowhere else. Use --rescue to push it to the gate"
                    + " first, or --force to discard it.");
            err.flush();
            return 65;
        }
        if (work != null && rescue && !running) {
            // Rescue pushes from inside the container to the gate, and neither is up.
            err.println("sokar: " + container + " is stopped, so its work cannot be pushed."
                    + " Resume it with 'sokar task resume " + container + "' first.");
            err.flush();
            return 70;
        }
        if (work != null && rescue && !rescueWork(container, out, err)) {
            err.println("sokar: nothing was removed, because the work could not be rescued");
            err.flush();
            return 70;
        }

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
            // Written on the way down, while the answer is still knowable. Whoever removes this
            // task later cannot ask the container itself.
            if (running) {
                UnhandedWork.note(state, work);
            }
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

    /**
     * Describes what the agent has done and not handed back, or {@code null} when there is none.
     * <p>
     * Only answerable while the container runs, which is why it is asked before anything is
     * stopped. A task with no workspace, or one already stopped, simply reports nothing rather
     * than guessing.
     *
     * @param container Container name.
     * @return A phrase naming what would be lost, or {@code null}.
     */
    private String unhandedWork(String container) {

        final org.fuin.sokar.core.process.CommandResult result =
                context.podman().ask(container, java.util.Map.of(), java.util.List.of("sh", "-c",
                        "cd " + TaskWorkspace.MOUNT + " 2>/dev/null || exit 0;"
                        + " printf '%s %s' \"$(git status --porcelain 2>/dev/null | wc -l)\""
                        + " \"$(git log --oneline --branches --not --remotes 2>/dev/null"
                        + " | wc -l)\""));
        if (!result.successful()) {
            return null;
        }
        return UnhandedWork.phrase(result.trimmedOutput());
    }

    /**
     * Pushes what the agent never handed back, under a ref of its own.
     * <p>
     * Rescued work is not work an agent offered up, so it lands beside the reviewed ref rather
     * than in it, and a reviewer can tell the two apart. Only a gated task can be rescued: a task
     * that pushes straight to a real upstream has no place to put unreviewed work.
     *
     * @param container Container name.
     * @param out Where progress is reported.
     * @param err Where failures are reported.
     * @return {@code true} if the work is now on the gate.
     */
    private boolean rescueWork(String container, PrintWriter out, PrintWriter err) {

        final org.fuin.sokar.core.process.CommandResult reference = context.podman().ask(container,
                java.util.Map.of(),
                java.util.List.of("sh", "-c", "printf '%s' \"$SOKAR_TASK_REF\""));
        if (!reference.successful()) {
            err.println("sokar: the task has no gate ref, so there is nowhere to rescue it to");
            return false;
        }
        final String taskRef = reference.trimmedOutput();
        if (!taskRef.startsWith("refs/sokar/incoming/")) {
            err.println("sokar: this task pushes straight to its upstream, so unreviewed work"
                    + " cannot be rescued without publishing it");
            return false;
        }

        final String rescueRef = taskRef + "-rescued";
        final org.fuin.sokar.core.process.CommandResult pushed = context.podman().ask(container,
                java.util.Map.of("SOKAR_TASK_REF", rescueRef), TaskWorkspace.pushCommand());
        // The output is checked as well as the exit code: this answer decides whether a container
        // holding the only copy of something is removed, so "it did not fail" is not enough.
        if (!pushed.successful() || pushed.trimmedOutput().contains("nothing to push")) {
            err.println("sokar: could not push the work: " + (pushed.trimmedOutput().isEmpty()
                    ? pushed.standardError().strip() : pushed.trimmedOutput()));
            return false;
        }
        out.println("rescued   " + rescueRef);
        out.println("          review it with 'sokar gate pending'");
        return true;
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
