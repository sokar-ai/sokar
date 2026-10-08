package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Says everything this machine knows about one task.
 * <p>
 * <strong>Why it had to exist.</strong> {@link TaskInventory} computes sixteen things about a
 * task and {@code task list} renders five of them, so the CLI showed a third of what the daemon
 * hands an interface. Worse, the one somebody asks for most - whether the workspace holds changes
 * nobody has pushed - was reachable only by trying to destroy the task and reading the refusal.
 * A destructive command used as a query is a missing query.
 * <p>
 * <strong>What it cannot always answer.</strong> Uncommitted work is a question for the
 * workspace, and the workspace is inside the container: while it runs, this asks it; once it is
 * stopped, the only source is the note {@code task stop} wrote on the way out. A task stopped by
 * something else - a reboot, a kill - has neither, and this says so rather than reporting
 * "nothing", which would read as "nothing to lose".
 */
@Command(name = "status",
        mixinStandardHelpOptions = true,
        description = "Says what a task is doing and what its workspace holds.")
public class TaskStatusCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Parameters(index = "0", arity = "0..1", paramLabel = "TASK",
            description = "Container name, as shown by 'sokar task list'. Default: the task started from the checkout you stand in.")
    private @org.jspecify.annotations.Nullable String container;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public List<String> candidates() {
        // Every task: this reads and changes nothing, so there is no state a task could be in
        // that makes asking about it the wrong thing to do.
        return TaskCandidates.all(context);
    }

    @Override
    public String candidateLabel() {
        return "tasks";
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

        if (!org.fuin.sokar.runtime.ContainerName.isTask(container)) {
            err.println("sokar: '" + container + "' is not a task of this machine");
            Suggests.offer(err, this);
            err.flush();
            return 64;
        }

        final TaskInventory.Task task = TaskInventory.deriving(context).tasks().stream()
                .filter(each -> each.name().equals(container)).findFirst().orElse(null);
        if (task == null) {
            err.println("sokar: there is no task called " + container);
            Suggests.offer(err, this);
            err.flush();
            return 69;
        }

        line(out, "task", task.name());
        line(out, "label", task.label());
        line(out, "project", task.project());
        line(out, "class", task.securityClass());
        line(out, "agent", task.agent());
        line(out, "provider", task.provider());
        line(out, "mode", task.mode());
        out.println();
        line(out, "state", task.state());
        line(out, "activity", task.activity().name().toLowerCase(java.util.Locale.ROOT));
        if (task.agentEnded() != null) {
            // Its own line, and coloured when it is an error: the run is over, and only a person can do anything now.
            final String ended = endedLine(task.agentEnded());
            line(out, "ended", task.agentEnded().finished() && task.agentEnded().unpushed() == 0 ? ended
                    : alarm(ended));
        }
        line(out, "waiting", task.waitingFor());
        // Read from the agent's own screen by a rule its package declares: a reading, said as one.
        line(out, "screen", screenLine(task.derived()));
        line(out, "last said", task.derived().lastMessage().isEmpty() ? null
                : TalkReadCommand.shown(task.derived().lastMessage()));
        line(out, "session", task.derived().session().isEmpty() ? null
                : task.derived().session() + " - the next start continues it");
        // Both: the age is what somebody is asking, and the instant is what they would quote
        // in a report or compare against something else.
        final String age = Age.compact(task.since(), java.time.Instant.now());
        line(out, "since", age.isEmpty() ? task.since()
                : age + " ago" + (task.since().isBlank() ? "" : "  (" + task.since() + ")"));
        line(out, "helpers", String.valueOf(task.helpers()));
        // 'off' is the one clearance value that has to be visible wherever a task is: nothing
        // asks and nothing is refused. It is the other thing on this page worth a colour.
        line(out, "clearance", "off".equals(task.clearance())
                ? warn("off - nothing asks and nothing is refused") : task.clearance());
        out.println();
        line(out, "branch", task.branch());
        line(out, "reviewing", task.waiting() > 0 ? task.waiting() + " waiting at the gate" : null);
        line(out, "workspace", workspace(task));
        line(out, "prompt", task.prompt());
        // What was handed to it, from the record: name, size, by whom - so a person returning to a task sees what
        // it was given without going in and looking.
        for (final HandIns.HandedFile file : task.handIns().files()) {
            line(out, "file", "/sokar/files/" + file.name() + "  " + file.bytes() + " bytes, by " + file.by()
                    + ", " + file.at());
        }
        // Which forge follows its pushes, what keeps it from doing so, and what each push's build did.
        final TaskBuilds.View builds = task.builds();
        if (!builds.reader().isEmpty()) {
            line(out, "builds", builds.problem().isEmpty()
                    ? builds.reader() + (builds.builds().isEmpty() ? ", no push yet" : "")
                    : warn(builds.reader() + " - " + builds.problem()));
        }
        for (final TaskBuilds.Seen seen : builds.builds()) {
            final long failed = seen.jobs().stream().filter(job -> "failure".equals(job.result())).count();
            line(out, "build", seen.commit().substring(0, Math.min(12, seen.commit().length())) + "  " + seen.verdict()
                    + (seen.jobs().isEmpty() ? "" : ", " + seen.jobs().size() + " jobs"
                            + (failed == 0 ? "" : ", " + failed + " failed"))
                    + " since " + seen.since() + (seen.detail().isEmpty() ? "" : " - " + seen.detail()));
        }
        out.flush();
        return 0;
    }

    /**
     * Describes what the workspace holds, or why that cannot be said.
     *
     * @param task The task.
     * @return One phrase.
     */
    /**
     * Says how a task's agent ended, as one line.
     * <p>
     * A provider's refusal is named as the provider's, with its status: the person looks there - credits, key,
     * limits - and not at Sokar. Work written and never pushed is said with the one command that saves it.
     *
     * @param ended How it ended.
     * @return The line, without its label.
     */
    static String endedLine(AgentEnding.Ended ended) {
        final StringBuilder line = new StringBuilder();
        if (ended.finished()) {
            line.append("finished at ").append(ended.at());
        } else {
            line.append("with an error at ").append(ended.at()).append(": ");
            if (org.fuin.sokar.agent.api.AgentEnd.PROVIDER.equals(ended.source())) {
                line.append(ended.status() == null ? "" : ended.status() + " from ").append("the provider");
            } else {
                line.append("the agent");
            }
            if (!ended.error().isBlank()) {
                line.append(" - ").append(TalkReadCommand.shown(ended.error()));
            }
        }
        if (ended.unpushed() > 0) {
            line.append("; ").append(ended.unpushed()).append(ended.unpushed() == 1 ? " change" : " changes")
                    .append(" never pushed to the gate - 'sokar task remove TASK --rescue' pushes it there");
        }
        return line.toString();
    }

    private String workspace(TaskInventory.Task task) {
        final UnhandedWork.Held held = task.running()
                ? new TaskControl(context).heldBy(task.name())
                : UnhandedWork.read(context.paths().tasks().containerState(task.name()));
        if (!held.readable()) {
            // Not "nothing": nothing would read as nothing to lose, and what is true is that
            // nobody could look.
            return warn("cannot be read - the task is stopped and left no record");
        }
        final String phrase = held.phrase();
        if (phrase == null) {
            return "everything is on the gate"
                    + (task.running() ? "" : ", as recorded when it stopped");
        }
        // Coloured because of what it means, not because it is interesting: this is work that
        // exists nowhere else, and removing the task destroys it.
        return alarm(phrase + " in the container, never pushed to the gate"
                + (task.running() ? "" : " - as recorded when it stopped"));
    }

    /**
     * Marks something that can be lost.
     * <p>
     * <strong>Through picocli's own {@code Ansi.AUTO}</strong>, which paints only when the output
     * is a terminal and honours {@code NO_COLOR}. Writing the escapes directly would put them in
     * a pipe, a log and a test fixture, where they are noise at best - and this output is read by
     * scripts as well as by people.
     *
     * @param text What to mark.
     * @return The text, painted or not.
     */
    private static String alarm(String text) {
        return picocli.CommandLine.Help.Ansi.AUTO.string("@|bold,red " + text + "|@");
    }

    /**
     * Marks something that is not wrong but must not be read as fine.
     *
     * @param text What to mark.
     * @return The text, painted or not.
     */
    private static String warn(String text) {
        return picocli.CommandLine.Help.Ansi.AUTO.string("@|yellow " + text + "|@");
    }

    private static void line(PrintWriter out,
            String name, @org.jspecify.annotations.Nullable String value) {
        // Absent fields are left out rather than printed as "-". This is a page somebody reads
        // rather than a table they scan, and a column of dashes is noise where a short page is
        // the point.
        if (value != null && !value.isBlank()) {
            out.printf("%-10s %s%n", name, value);
        }
    }

    /**
     * Says what the agent's screen shows, as a reading rather than a fact.
     *
     * @param derived What was derived.
     * @return The line.
     */
    static String screenLine(AgentWaiting.Derived derived) {
        final String reading = switch (derived.screen()) {
            case WAITING -> "reads as waiting for you" + (derived.waitingFor().isEmpty() ? ""
                    : " - " + TalkReadCommand.shown(derived.waitingFor()));
            case NOT_WAITING -> "reads as not waiting";
            case UNDECLARED -> "cannot say - the agent declares nothing to read it by";
            case UNSEEN -> "cannot say - nothing here sees its screen";
        };
        return derived.unproven() ? reading + " (its rules have not matched in a day - check the agent's version)"
                : reading;
    }
}
