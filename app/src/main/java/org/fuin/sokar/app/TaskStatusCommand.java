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

    @Parameters(index = "0", paramLabel = "TASK",
            description = "Container name, as shown by 'sokar task list'.")
    private String container;

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

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        if (!org.fuin.sokar.runtime.ContainerName.isTask(container)) {
            err.println("sokar: '" + container + "' is not a task of this machine");
            Suggests.offer(err, this);
            err.flush();
            return 64;
        }

        final TaskInventory.Task task = new TaskInventory(context).tasks().stream()
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
        line(out, "mode", task.mode());
        out.println();
        line(out, "state", task.state());
        line(out, "activity", task.activity().name().toLowerCase(java.util.Locale.ROOT));
        line(out, "waiting", task.waitingFor());
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
        out.flush();
        return 0;
    }

    /**
     * Describes what the workspace holds, or why that cannot be said.
     *
     * @param task The task.
     * @return One phrase.
     */
    private String workspace(TaskInventory.Task task) {
        final UnhandedWork.Held held = task.running()
                ? new TaskControl(context).heldBy(container)
                : UnhandedWork.read(context.paths().containerState(container));
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
}
