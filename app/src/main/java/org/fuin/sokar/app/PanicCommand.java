package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Stops every running task and every helper any of them started.
 * <p>
 * <strong>It stops; it never removes.</strong> The reason for reaching for this is that something
 * is going wrong and nobody yet knows what, which is exactly when destroying the evidence is
 * worst: the workspace, the logs and what never reached the gate all survive, and
 * {@code task resume} brings a task back with the work it had. Discarding is a separate decision,
 * made afterwards, by name.
 * <p>
 * No container names, on purpose. Somebody reaching for this is not in a position to list what is
 * running first, and a stop that needs an inventory is one they will get wrong.
 */
@Command(name = "panic",
        mixinStandardHelpOptions = true,
        description = "Stops every running task and every helper, without removing anything.")
public class PanicCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--dry-run",
            description = "Lists what would be stopped and stops nothing.")
    private boolean dryRun;

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

        final List<TaskInventory.Task> running = new TaskInventory(context).tasks().stream()
                .filter(TaskInventory.Task::running)
                .toList();

        if (running.isEmpty()) {
            out.println("nothing is running");
            out.flush();
            return 0;
        }

        if (dryRun) {
            running.forEach(task -> out.println("would stop " + task.name()
                    + " (" + task.helpers() + " helpers)"));
            out.flush();
            return 0;
        }

        final TaskControl control = new TaskControl(context);
        int stopped = 0;
        final List<String> surviving = new java.util.ArrayList<>();

        for (final TaskInventory.Task task : running) {
            // Through the same operation 'task stop' uses, so a panic writes down what a task held
            // that never reached the gate, exactly as a deliberate stop does. A faster path that
            // skipped that would lose the one record of what was lost.
            final TaskControl.Stopped result = control.stop(task.name(), false, false, false);
            out.println("stopped   " + task.name() + " (" + result.helpers() + " helpers)");
            if (result.surviving().isEmpty()) {
                stopped++;
            } else {
                surviving.addAll(result.surviving());
            }
        }

        out.println();
        out.println("stopped   " + stopped + " of " + running.size() + " tasks");
        // Never removed, and said every time: an operator who believes this cleaned up would go
        // looking for work that is still exactly where it was.
        out.println("kept      every workspace, log and unpushed commit; 'sokar task list' shows"
                + " them, 'sokar task resume <name>' brings one back");
        out.flush();

        if (!surviving.isEmpty()) {
            // Named rather than counted: these are processes an operator has to kill themselves.
            surviving.forEach(alive -> err.println("sokar: still running: " + alive));
            err.flush();
            return 70;
        }
        return 0;
    }
}
