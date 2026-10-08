package org.fuin.sokar.app;

import picocli.CommandLine.Command;

/**
 * The {@code sokar task} command group.
 */
@Command(name = "task",
        aliases = { "tasks" },

        mixinStandardHelpOptions = true,
        description = "Runs and inspects agent tasks.",
        subcommands = { TaskRunCommand.class, TaskListCommand.class, TaskStopCommand.class, TaskRefreshCommand.class,
                TaskRemoveCommand.class, TaskLabelCommand.class, TaskAttachCommand.class,
                TaskStatusCommand.class, TaskLogsCommand.class,
                TaskPrepareCommand.class,
                TaskClearanceCommand.class, TaskGiveCommand.class, TaskTakeBackCommand.class,
                TaskFilesCommand.class, TaskWatchBuildsCommand.class })
public class TaskCommand implements java.util.concurrent.Callable<Integer>,
        SokarFactory.ContextAware {

    @picocli.CommandLine.Spec
    private picocli.CommandLine.Model.CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Lists, when no verb was given.
     * <p>
     * Because {@code sokar tasks} should answer the question it looks like it is asking, and
     * because the same is true of {@code sokar projects}. Help for a command whose name is a noun
     * is an answer nobody wanted.
     *
     * @return Exit code.
     */
    @Override
    public Integer call() {
        final TaskListCommand listing = new TaskListCommand();
        listing.setContext(context);
        return listing.render(spec.commandLine().getOut());
    }
}
