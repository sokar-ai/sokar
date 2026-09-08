package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Turns enforcement on or off on a task that is already running.
 * <p>
 * The state was chosen when the task started and could not be changed, so somebody watching a task
 * ask about the same host for the twentieth time had to stop it and start again to make it stop.
 */
@Command(name = "clearance",
        mixinStandardHelpOptions = true,
        description = "Changes what a running task does about a blocked connection.")
public class TaskClearanceCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name.")
    private String task;

    @Parameters(index = "1", paramLabel = "<mode>",
            description = "prompt, allow, deny or off.")
    private String mode;

    @Option(names = "--dry-run", description = "Says what would happen and changes nothing.")
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

        final RunningClearance.Result result =
                RunningClearance.set(context, task, mode, dryRun, out);
        out.flush();

        switch (result.outcome()) {
            case CHANGED -> out.println("clearance " + result.was() + " -> " + result.now());
            case UNCHANGED -> out.println("clearance already " + result.was() + ", unchanged");
            case PREVIEWED -> out.println("would change clearance " + result.was() + " -> "
                    + result.now());
            default -> {
                err.println("sokar: " + result.detail());
                err.flush();
                return result.outcome() == RunningClearance.Outcome.FAILED ? 70 : 69;
            }
        }
        out.flush();
        return 0;
    }
}
