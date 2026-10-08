package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.Repository;
import org.fuin.sokar.gate.GateException;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.gate.LocalRepository;
import org.fuin.sokar.gate.PendingPush;
import org.jspecify.annotations.Nullable;

/**
 * Brings the work of a task started in a checkout back into that checkout, as the branch {@code sokar/<task>}, after
 * showing it and asking.
 * <p>
 * Everything {@code sokar gate approve} needs is known in the checkout: its project and repository follow from its
 * {@code origin} as they did when the task started, the upstream is the checkout itself, and the branch is named after
 * the task. What comes is what waits at the gate - the agent commits and pushes there - never what is only in the
 * container, and nothing goes to a remote of the checkout: the push goes to the checkout's own directory.
 */
final class CheckoutApproval {

    /** Where a task's work arrives in the checkout: a branch of its own, never the one checked out. */
    static final String PREFIX = "sokar/";

    /** How much of the diff is shown before the question; the rest is one command away. */
    static final int SHOWN_LINES = 120;

    /** Exit: nothing to bring back, or nothing to bring it into. */
    static final int NOTHING = 66;

    /** Exit: several tasks and none named where nobody can be asked. */
    static final int AMBIGUOUS = 64;

    /** Exit: the person said no, or the branch would not fast-forward; nothing moved. */
    static final int NOT_MOVED = 1;

    /**
     * Asks the person a question.
     */
    @FunctionalInterface
    interface Asker {

        /**
         * Asks.
         *
         * @param question What is asked, without a line break.
         * @return The answer, or {@code null} when nobody answers.
         */
        @Nullable String ask(String question);
    }

    private final SokarContext context;

    private final PrintWriter out;

    private final PrintWriter err;

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param out Where what comes is shown.
     * @param err Where a refusal goes.
     */
    CheckoutApproval(final SokarContext context, final PrintWriter out, final PrintWriter err) {
        this.context = context;
        this.out = out;
        this.err = err;
    }

    /**
     * Brings a task's work back.
     *
     * @param directory Where the person stands, in the checkout.
     * @param task The task's name, or {@code null} for the one started from this checkout.
     * @param yes Whether to bring it without asking.
     * @param asker How the person is asked.
     * @return 0 when the branch holds the work, otherwise why nothing moved.
     */
    int run(final Path directory, final @Nullable String task, final boolean yes, final Asker asker) {
        final Path top = LocalRepository.topLevel(context.runner(), directory);
        if (top == null) {
            return refuse(NOTHING, directory.toAbsolutePath() + " is in no git checkout; run it in the checkout a task"
                    + " was started from");
        }
        final GitGate gate;
        try {
            gate = gateOf(top);
        } catch (ProjectException ex) {
            return refuse(NOTHING, String.valueOf(ex.getMessage()));
        }
        if (gate == null || !Files.isDirectory(gate.mirror())) {
            return refuse(NOTHING, "no task was started from " + top + ", so nothing waits at the gate for it");
        }
        final List<PendingPush> waiting = gate.pendingDetail();
        final PendingPush chosen = choose(waiting, task, yes, top, asker);
        if (chosen == null) {
            return waiting.isEmpty() || task != null ? NOTHING : AMBIGUOUS;
        }
        final String branch = PREFIX + chosen.name();
        show(gate, chosen);
        final String refused = refusal(top, gate.mirror(), branch, chosen.commit());
        if (refused != null) {
            return refuse(NOT_MOVED, refused);
        }
        if (!yes) {
            final String answer = asker.ask("Bring this into " + top + " as " + branch + "? [y/N]");
            if (answer == null || !List.of("y", "yes").contains(answer.strip().toLowerCase(java.util.Locale.ROOT))) {
                out.println("nothing brought back; the work still waits at the gate");
                out.flush();
                return NOT_MOVED;
            }
        }
        try {
            gate.approve(chosen.name(), branch, chosen.commit());
        } catch (GateException ex) {
            return refuse(NOT_MOVED, ex.getMessage() + "; nothing moved, the work still waits at the gate");
        }
        out.println("brought   " + chosen.name() + " into " + top + " as " + branch + " ("
                + chosen.commit().substring(0, Math.min(12, chosen.commit().length())) + ")");
        out.flush();
        return 0;
    }

    /**
     * The gate of the repository this checkout is the source of, forwarding to the checkout; {@code null} when no task
     * was started from it.
     *
     * @throws ProjectException When the checkout's repository takes its work from a remote, which is where the work
     *         goes, never into this checkout.
     */
    private @Nullable GitGate gateOf(final Path top) {
        final DefaultProject.Entry own = new DefaultProject(context).withUpstream(top.toString());
        if (own != null) {
            final Project project = GateSupport.byName(context, DefaultProject.NAME);
            final Repository repository = project.repository(own.name());
            return repository == null ? null : GateSupport.gate(context, project, repository, null, null);
        }
        // Not its own source: a followed project naming its origin, or a repository added before the checkout was
        // one. Its work goes to the remote - pushed there only by a person who says so, never by a question here.
        final CommandResult remote = context.runner().run(Command.of("git", "-C", top.toString(), "remote", "get-url",
                "origin"));
        final String origin = RepositoryAddress.withoutCredential(remote.successful()
                ? remote.standardOutput().strip() : "");
        if (origin.isEmpty()) {
            return null;
        }
        WorkOrigin.Choice choice = WorkOrigin.followedNaming(context, origin);
        if (choice == null) {
            final DefaultProject.Entry entry = new DefaultProject(context).withUpstream(origin);
            if (entry == null) {
                return null;
            }
            choice = new WorkOrigin.Choice(DefaultProject.NAME, entry.name(), "");
        }
        final GitGate gate = GateSupport.gate(context, GateSupport.byName(context, choice.project()),
                java.util.Objects.requireNonNull(GateSupport.byName(context, choice.project())
                        .repository(choice.repository())), null, null);
        if (Files.isDirectory(gate.mirror()) && !gate.pendingDetail().isEmpty()) {
            throw new ProjectException("the work of tasks on '" + choice.repository() + "' in '" + choice.project()
                    + "' goes to " + origin + ", not into this checkout: review it with 'sokar gate review' and send it"
                    + " there with 'sokar gate approve <task> <branch>'");
        }
        return null;
    }

    /** The push to bring back: the one named, the only one, or the one the person picks. */
    private @Nullable PendingPush choose(final List<PendingPush> waiting, final @Nullable String task,
            final boolean yes, final Path top, final Asker asker) {
        if (waiting.isEmpty()) {
            final String held = held(top);
            refuse(NOTHING, "nothing waits at the gate for a task started from " + top + (held.isEmpty()
                    ? "; the agent commits and pushes its work there when it is done"
                    : "; but " + held + ". In the task, commit and 'git push' - its remote is the gate"));
            return null;
        }
        final List<String> names = waiting.stream().map(PendingPush::name).toList();
        String wanted = task;
        if (wanted == null && waiting.size() > 1) {
            if (yes) {
                refuse(AMBIGUOUS, "work of several tasks waits at the gate: " + String.join(", ", names)
                        + "; name the one to bring back");
                return null;
            }
            wanted = asker.ask("Work of several tasks waits: " + String.join(", ", names) + ". Which one?");
            if (wanted == null) {
                refuse(AMBIGUOUS, "no task named; nothing brought back");
                return null;
            }
        }
        if (wanted == null) {
            return waiting.getFirst();
        }
        final String name = wanted.strip();
        for (final PendingPush each : waiting) {
            if (each.name().equals(name)) {
                return each;
            }
        }
        refuse(NOTHING, "no work of '" + name + "' waits at the gate for " + top + "; waiting: "
                + String.join(", ", names));
        return null;
    }

    /**
     * What the tasks started from this checkout hold that is not at the gate: what a running one has, asked, and what
     * a stopped one had when it stopped. "" when nothing is known to be held.
     */
    private String held(final Path top) {
        final DefaultProject.Entry entry = new DefaultProject(context).withUpstream(top.toString());
        if (entry == null) {
            return "";
        }
        final List<String> held = new java.util.ArrayList<>();
        for (final org.fuin.sokar.runtime.ContainerSummary task : context.podman().sokarTasks()) {
            if (!DefaultProject.NAME.equals(task.project()) || !entry.name().equals(task.repository())) {
                continue;
            }
            final UnhandedWork.Held work = task.running() ? UnhandedWork.ask(context.podman(), task.name())
                    : UnhandedWork.read(context.paths().tasks().containerState(task.name()));
            if (work.anything()) {
                held.add("the task " + task.name() + " holds " + work.phrase() + " that " + (work.unpushedCommits()
                        + work.changedFiles() == 1 ? "is" : "are") + " not at the gate");
            }
        }
        return String.join("; ", held);
    }

    /** The commits and the start of the diff, before anybody is asked. */
    private void show(final GitGate gate, final PendingPush chosen) {
        out.println("task      " + chosen.name());
        out.println(gate.log(chosen.name(), "HEAD").strip());
        out.println();
        final List<String> diff = gate.review(chosen.name(), "HEAD").lines().toList();
        diff.stream().limit(SHOWN_LINES).forEach(out::println);
        if (diff.size() > SHOWN_LINES) {
            out.println("... " + (diff.size() - SHOWN_LINES) + " more lines; 'sokar gate review' shows all of it");
        }
        out.flush();
    }

    /** Why the branch must not move: it is the one checked out, or it holds work the push does not extend. */
    private @Nullable String refusal(final Path top, final Path mirror, final String branch, final String commit) {
        final CommandResult current = context.runner().run(Command.of("git", "-C", top.toString(), "symbolic-ref",
                "--quiet", "--short", "HEAD"));
        if (current.successful() && current.standardOutput().strip().equals(branch)) {
            return branch + " is checked out in " + top + "; switch to another branch first, nothing moved";
        }
        final CommandResult existing = context.runner().run(Command.of("git", "-C", top.toString(), "rev-parse",
                "--verify", "--quiet", "refs/heads/" + branch));
        if (!existing.successful()) {
            return null;
        }
        // Asked in the gate's mirror, which holds the work and its history: a commit only the checkout has is the
        // person's own, and the work does not extend it.
        final CommandResult ancestor = context.runner().run(Command.of("git", "-C", mirror.toString(), "merge-base",
                "--is-ancestor", existing.standardOutput().strip(), commit));
        return ancestor.successful() ? null : branch + " exists in " + top + " and the work does not fast-forward it;"
                + " nothing moved, the work still waits at the gate";
    }

    private int refuse(final int code, final String why) {
        err.println("sokar: " + why);
        err.flush();
        return code;
    }
}
