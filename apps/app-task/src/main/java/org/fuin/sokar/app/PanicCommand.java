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
 * {@code task attach} offers to bring a task back with the work it had. Discarding is a separate decision,
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

        // The operation itself lives in TaskPanic, which the daemon serves too: this is the one
        // command somebody reaches for without knowing what is wrong, and two implementations of
        // "stop everything" is the last place a difference should be discovered.
        final TaskPanic.Result result = new TaskPanic(context).panic(dryRun);

        if (result.tasks().isEmpty()) {
            out.println("nothing is running");
            out.flush();
            return 0;
        }

        if (dryRun) {
            result.tasks().forEach(task -> out.println("would stop " + task.name()
                    + " (" + task.helpers() + " helpers)"));
            out.flush();
            return 0;
        }

        result.tasks().forEach(task -> out.println("stopped   " + task.name()
                + " (" + task.helpers() + " helpers)"));
        final List<String> surviving = result.surviving();

        out.println();
        out.println("stopped   " + result.clean() + " of " + result.tasks().size() + " tasks");
        // Never removed, and said every time: an operator who believes this cleaned up would go
        // looking for work that is still exactly where it was.
        out.println("kept      every workspace, log and unpushed commit; 'sokar task list' shows"
                + " them, 'sokar task attach <name>' offers to bring one back");
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
