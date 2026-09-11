package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Removes a task: its container, its state, and the workspace inside it.
 * <p>
 * <strong>Its own verb, not a flag on stop.</strong> Stopping ends an agent's session; removing
 * destroys its work. Those were one command with {@code --purge} between them, which is a verb
 * whose meaning depends on a word people do not read.
 * <p>
 * It refuses a running task rather than stopping one as a side effect, and it refuses a task whose
 * work exists nowhere else. Both refusals are answers, not errors: the caller decides and asks
 * again.
 */
@Command(name = "remove",
        mixinStandardHelpOptions = true,
        description = "Removes a stopped task, its container and its workspace.")
public class TaskRemoveCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Override
    public java.util.List<String> candidates() {
        // Every task, not only the stopped ones: a running task is a refusal with a named way out,
        // and offering no candidate for it would hide that the command applies at all.
        return TaskCandidates.all(context);
    }

    @Override
    public String candidateLabel() {
        return "tasks";
    }

    @Parameters(index = "0", paramLabel = "TASK",
            description = "Container name, as shown by 'sokar task list'.")
    private String container;

    @Option(names = "--rescue",
            description = "Pushes work the agent never handed back to the gate first, under its"
                    + " own ref, so removing the task does not destroy it.")
    private boolean rescue;

    @Option(names = "--force",
            description = "Removes it anyway: stops it if it runs, and discards work that never"
                    + " reached the gate.")
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
        // and not the other is a task removed, over the other surface, with work in it.
        final TaskControl.Stopped result = new TaskControl(context).remove(container, rescue, force);

        if (result.work() != null && result.outcome() != TaskControl.Outcome.NOT_A_TASK) {
            out.println("work      " + result.work() + " that never reached the gate");
        }

        switch (result.outcome()) {
            case NOT_A_TASK -> {
                err.println("sokar: '" + container + "' is not a task Sokar created");
                Suggests.offer(err, this);
                err.flush();
                return 64;
            }
            case STILL_RUNNING -> {
                err.println("sokar: refusing to remove " + container + ": it is running");
                err.println("       stop it with 'sokar task stop " + container + "' first,"
                        + " or --force to stop and remove it in one step.");
                err.flush();
                return 65;
            }
            case NOTHING_KNOWS -> {
                err.println("sokar: refusing to remove " + container + ": it is stopped and"
                        + " nothing recorded what it holds");
                // Not "start it to see": after a restart the record this needs is the one that is
                // gone, so that advice would name a way out that does not exist in this state.
                err.println("       --force discards it unseen.");
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
                        + " Start it with 'sokar task start' first.");
                err.flush();
                return 70;
            }
            case RESCUE_FAILED -> {
                if (result.detail() != null) {
                    err.println("sokar: " + result.detail());
                }
                err.println("sokar: nothing was removed, because the work could not be rescued");
                err.flush();
                return 70;
            }
            case NOTHING_TO_STOP -> {
                out.println("No task " + container + "; nothing to remove.");
                out.flush();
                return 0;
            }
            default -> {
                // Removed, which is everything below.
            }
        }

        if (result.rescuedRef() != null) {
            out.println("rescued   " + result.rescuedRef());
            out.println("          review it with 'sokar gate pending'");
        }
        out.println("removed   " + container);
        if (result.discarded() > 0) {
            // The other half of what a removal costs. The workspace has the gate to arrive in;
            // what the agent installed in the container has nowhere at all, and nothing else
            // records that it existed.
            out.println("discarded " + result.discarded() + " files the agent added inside the"
                    + " container - packages, caches, a built toolchain");
        }
        out.flush();
        return result.clean() ? 0 : 70;
    }
}
