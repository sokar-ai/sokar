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

    @Parameters(index = "0", arity = "0..1", paramLabel = "TASK",
            description = "Container name, as shown by 'sokar task list'. Default: the task started from the checkout you stand in.")
    private @org.jspecify.annotations.Nullable String container;

    @Option(names = "--rescue",
            description = "Pushes work the agent never handed back to the gate first, under its"
                    + " own ref, so removing the task does not destroy it.")
    private boolean rescue;

    @Option(names = "--force",
            description = "Removes it anyway: stops it if it runs, and discards work that never"
                    + " reached the gate.")
    private boolean force;

    @Option(names = "--no-input",
            description = "Asks nothing: a refusal names the option, as without a terminal.")
    private boolean noInput;

    @Option(names = "--yes",
            description = "Stops or starts the task where that is what removing it needs, without asking; never"
                    + " discards.")
    private boolean yes;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final TaskTarget.Found found = TaskTarget.resolve(context, container, java.nio.file.Path.of("."),
                TaskTarget.console(spec.commandLine().getOut()));
        if (found.container() == null) {
            spec.commandLine().getErr().println("sokar: " + found.refusal());
            if ((Object) this instanceof Suggests suggests) {
                Suggests.offer(spec.commandLine().getErr(), suggests);
            }
            spec.commandLine().getErr().flush();
            return found.code();
        }
        container = found.container();

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // Decided by TaskControl, which the daemon calls too: a refusal that exists in one caller
        // and not the other is a task removed, over the other surface, with work in it.
        TaskControl.Stopped result = new TaskControl(context).remove(container, rescue, force);
        if (!noInput && (yes || Offer.atTerminal())) {
            // What a refusal would name is offered instead, and the removal tried again after it: at most once per kind
            // of refusal, so a remedy that did not help ends in that refusal rather than in a loop.
            final Offer offer = new Offer(Offer.Asker.terminal(), noInput, yes, err);
            final java.util.Set<TaskControl.Outcome> offered = java.util.EnumSet.noneOf(TaskControl.Outcome.class);
            String next;
            while (offered.add(result.outcome()) && (next = nextTry(result.outcome(), container,
                    result.work() == null ? "" : String.valueOf(result.work()), offer)) != null) {
                switch (next) {
                    case "stop" -> new TaskControl(context).stop(container);
                    case "start" -> context.exec().applyAsInt(java.util.List.of(SokarBinary.path(), "task", "start",
                            container, "--detach"));
                    case "rescue" -> rescue = true;
                    default -> force = true;
                }
                if (next.equals("start")) {
                    rescue = true;
                }
                result = new TaskControl(context).remove(container, rescue, force);
            }
        }

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

    /**
     * Returns what to do before removing again, where a removal was refused for something a person can settle here:
     * {@code stop}, {@code start}, {@code rescue} or {@code force}; {@code null} when nothing is offered or it was
     * declined. Discarding is destructive: no by default, never by {@code --yes}.
     *
     * @param outcome Why the removal was refused.
     * @param container The task.
     * @param work What it holds that never reached the gate, as said, or "".
     * @param offer Who is asked.
     * @return What to do, or {@code null}.
     */
    static @org.jspecify.annotations.Nullable String nextTry(final TaskControl.Outcome outcome, final String container,
            final String work, final Offer offer) {
        return switch (outcome) {
            case STILL_RUNNING -> offer.confirm(container + " is running", "Stop it, then remove it?", "", false)
                    ? "stop" : null;
            case HOLDS_WORK -> {
                final String chosen = offer.choose(container + " holds " + work + ", which exists nowhere else",
                        java.util.List.of("push it to the gate, then remove the task",
                                "discard it with the task", "keep the task"),
                        "sokar task remove " + container + " --rescue");
                yield chosen == null || chosen.startsWith("keep") ? null
                        : chosen.startsWith("push") ? "rescue" : "force";
            }
            case NOTHING_KNOWS -> offer.confirm(container + " is stopped and nothing recorded what it holds",
                    "Discard it unseen?", "whatever the task holds, unseen", true) ? "force" : null;
            case RESCUE_NEEDS_IT_RUNNING -> offer.confirm(container + " is stopped, so its work cannot be pushed",
                    "Start it, push its work to the gate, then remove it?", "", false) ? "start" : null;
            default -> null;
        };
    }
}
