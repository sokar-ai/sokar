package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists the tasks on this machine.
 * <p>
 * The container runtime knows the containers; only Sokar knows which project and security class
 * each belongs to, which is what an operator with several tasks running actually needs to tell
 * them apart. Both come from the task's own state directory rather than from the container name,
 * because a project name may contain the separator the name is built with.
 */
@Command(name = "list",
        mixinStandardHelpOptions = true,
        description = "Lists the tasks on this machine.")
public class TaskListCommand implements Callable<Integer>, SokarFactory.ContextAware {

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
        // Asked of the inventory rather than assembled here: the daemon answers the same question
        // for the interface, and two implementations of it would drift.
        final List<TaskInventory.Task> tasks = new TaskInventory(context).tasks();

        if (tasks.isEmpty()) {
            out.println("No tasks.");
            out.flush();
            return 0;
        }

        // The age the state column had to drop. It is kept as an instant precisely so it can be
        // stated here rather than parsed back out of the runtime's own phrasing.
        final java.time.Instant now = java.time.Instant.now();
        out.printf("%-38s %-14s %-9s %-18s %-5s %s%n",
                "NAME", "PROJECT", "CLASS", "STATE", "AGE", "HELPERS");
        for (final TaskInventory.Task task : tasks) {
            final String age = Age.compact(task.since(), now);
            out.printf("%-38s %-14s %-9s %-18s %-5s %s%n",
                    task.name(),
                    task.project() == null ? "-" : task.project(),
                    task.securityClass() == null ? "-" : task.securityClass(),
                    state(task.state()),
                    age.isEmpty() ? "-" : age,
                    task.helpers());
        }
        out.flush();
        return 0;
    }

    /**
     * Returns the runtime's state, short enough to stay in its column.
     * <p>
     * The runtime writes a phrase, and its longest - {@code Exited (143) Less than a second ago} -
     * is twice the width of the column, so it ran into the helper count and the row stopped being
     * a table. The age is what gets dropped: this is a list of what exists and whether it is up,
     * and {@code task list} is how an operator finds the name to stop or resume.
     *
     * @param state What the runtime called it.
     * @return The state, at most as wide as its column.
     */
    private static String state(String state) {
        // 'Exited (143) Less than a second ago' and 'Exited (0) 2 minutes ago' both carry an age
        // this table has no room for, and the exit code is the part worth keeping: it is how a
        // task that was stopped is told apart from one that died.
        final int code = state.startsWith("Exited (") ? state.indexOf(')') : -1;
        final String shortened = code > 0 ? state.substring(0, code + 1) : state;
        return shortened.length() <= 18 ? shortened : shortened.substring(0, 17) + "…";
    }
}
