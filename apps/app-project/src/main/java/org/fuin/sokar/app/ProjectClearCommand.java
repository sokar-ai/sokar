package org.fuin.sokar.app;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Clears one project from this machine in one step: its tasks with their workspaces and mailboxes, its mirrors,
 * the follow, its conversation and what this machine kept for it, and its deploy keys from the vault. Lists it
 * first; {@code --yes} clears it.
 */
@Command(name = "clear",
        mixinStandardHelpOptions = true,
        description = "Clears a project from this machine: tasks, mailboxes, mirrors, the follow, its conversation"
                + " and deploy keys. Lists it first; --yes clears it.")
public class ProjectClearCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Override
    public java.util.List<String> candidates() {
        return new ProjectInventory(context).projects().stream().map(ProjectInventory.Summary::name).toList();
    }

    @Override
    public String candidateLabel() {
        return "projects";
    }

    @Parameters(index = "0", paramLabel = "<name>", description = "The project.")
    private String name;

    @Option(names = "--yes", description = "Clears what the listing names. Without it, nothing is removed.")
    private boolean yes;

    @Option(names = "--dry-run", description = "Lists what would go, and removes nothing.")
    private boolean dryRun;

    @Option(names = "--force",
            description = "Clears work nobody reviewed and running tasks too. That work is destroyed.")
    private boolean force;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        // What the vault holds for it is listed and cleared too: at a terminal a shut vault is opened for this alone.
        context.openIfShut(true, false, spec.commandLine().getErr());
        final boolean listOnly = dryRun || !yes;
        return ClearCommand.render(new Clearing(context).project(name, listOnly, force), listOnly, !dryRun && !yes,
                spec.commandLine().getOut());
    }
}
