package org.fuin.sokar.app;

import java.io.PrintWriter;
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

        // Decided by TaskControl, which the daemon calls too: a refusal that exists in one caller
        // and not the other is a task removed with work in it. This renders, and nothing else.
        final TaskControl.Stopped result =
                new TaskControl(context).stop(container, purge, rescue, force);

        if (result.work() != null && result.outcome() != TaskControl.Outcome.NOT_A_TASK) {
            out.println("work      " + result.work() + " that never reached the gate");
        }

        switch (result.outcome()) {
            case NOT_A_TASK -> {
                // Refused rather than guessed at: this command stops processes and removes a
                // container, and a name Sokar did not create belongs to somebody else.
                err.println("sokar: '" + container + "' is not a task Sokar created");
                err.flush();
                return 64;
            }
            case NOTHING_KNOWS -> {
                err.println("sokar: refusing to remove " + container + ": it is stopped and"
                        + " nothing recorded what it holds");
                err.println("       resume it with 'sokar task resume " + container + "' to see,"
                        + " or --force to discard it unseen.");
                err.flush();
                return 65;
            }
            case HOLDS_WORK -> {
                err.println("sokar: refusing to remove " + container + ": it holds "
                        + result.work());
                err.println("       it exists nowhere else. Use --rescue to push it to the gate"
                        + " first, or --force to discard it.");
                err.flush();
                return 65;
            }
            case RESCUE_NEEDS_IT_RUNNING -> {
                err.println("sokar: " + container + " is stopped, so its work cannot be pushed."
                        + " Resume it with 'sokar task resume " + container + "' first.");
                err.flush();
                return 70;
            }
            case RESCUE_FAILED -> {
                // The reason first: a task with no gate ref, one that pushes to its upstream and
                // a push that did not arrive need three different things from the operator.
                if (result.detail() != null) {
                    err.println("sokar: " + result.detail());
                }
                err.println("sokar: nothing was removed, because the work could not be rescued");
                err.flush();
                return 70;
            }
            case NOTHING_TO_STOP -> {
                out.println("No task " + container + "; nothing to stop.");
                out.flush();
                return 0;
            }
            default -> {
                // Stopped, which is everything below.
            }
        }

        if (result.rescuedRef() != null) {
            out.println("rescued   " + result.rescuedRef());
            out.println("          review it with 'sokar gate pending'");
        }
        out.println("stopped   " + container + (result.removed() ? " and removed" : ""));
        out.println("helpers   " + (result.helpers() - result.surviving().size())
                + " of " + result.helpers() + " stopped");
        for (final String alive : result.surviving()) {
            out.println("          still running: " + alive);
        }
        if (result.removed()) {
            out.println("removed   " + context.paths().containerState(container));
        } else if (result.state() != null) {
            out.println("resume    sokar task resume " + container);
            out.println("logs      " + result.state());
        }
        out.flush();
        return result.clean() ? 0 : 70;
    }
}
