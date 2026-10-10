package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Takes a file handed to a running task out of {@code /sokar/files} again, and writes that down.
 */
@Command(name = "take-back",
        mixinStandardHelpOptions = true,
        description = "Takes a handed-in file out of a running task's /sokar/files again.")
public class TaskTakeBackCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Parameters(index = "0", paramLabel = "TASK", description = "A running task, as shown by 'sokar task list'.")
    private String container;

    @Parameters(index = "1", paramLabel = "NAME", description = "Its name in /sokar/files.")
    private String name;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public List<String> candidates() {
        return TaskCandidates.running(context);
    }

    @Override
    public String candidateLabel() {
        return "running tasks";
    }

    @Override
    public Integer call() {
        // By the container's name or the task's own, as every task command takes it.
        container = TaskTarget.spelled(context, container);
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (!TaskCandidates.running(context).contains(container)) {
            err.println("sokar: no running task named '" + container + "' - " + Shown.tasks(context));
            err.flush();
            return 69;
        }
        try {
            final HandIns.HandedFile taken = HandIns.of(context).takeBack(container, name,
                    System.getProperty("user.name", ""));
            out.println("taken back " + container + ": /sokar/files/" + taken.name());
            out.flush();
            return 0;
        } catch (HandIns.Refused refused) {
            err.println("sokar: " + refused.getMessage());
            err.flush();
            return 69;
        } catch (IOException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
