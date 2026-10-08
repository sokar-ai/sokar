package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Clears this account: every project on it, the built-in project's tasks, and what each transport keeps for it.
 * <p>
 * One step, confirmed once: without {@code --yes} it lists what would go and removes nothing, so the list a person
 * confirms is the list that runs. What only a person's credentials can remove - deploy keys at a forge, this
 * machine's line in a project's {@code machine-signers} - is named, never attempted.
 */
@Command(name = "clear",
        mixinStandardHelpOptions = true,
        description = "Clears this account: every project, task, mailbox, mirror and follow, and what the transports"
                + " keep. Lists it first; --yes clears it.")
public class ClearCommand implements Callable<Integer>, SokarFactory.ContextAware {

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
        final boolean listOnly = dryRun || !yes;
        return render(new Clearing(context).account(listOnly, force), listOnly, !dryRun && !yes,
                spec.commandLine().getOut());
    }

    /**
     * Prints what a clearing did, one line each, the same for an account and a project.
     *
     * @param result What it did.
     * @param listOnly Whether nothing was removed.
     * @param askYes Whether to say that {@code --yes} clears it.
     * @param out Where to print.
     * @return The exit code: 0, or 1 when something could not be removed or it was refused.
     */
    static int render(final Clearing.Result result, final boolean listOnly, final boolean askYes,
            final PrintWriter out) {
        if (result.items().isEmpty()) {
            out.println("nothing to clear");
            out.flush();
            return 0;
        }
        boolean failed = result.refused();
        for (final Clearing.Item item : result.items()) {
            final String said = switch (item.status()) {
                case REMOVED -> "removed     ";
                case WOULD_REMOVE -> "would go    ";
                case NOT_REMOVED -> "not removed ";
                case FOR_A_PERSON -> "for you     ";
            };
            failed |= item.status() == Clearing.Status.NOT_REMOVED;
            out.println(said + item.kind() + "  " + item.what() + (item.why().isEmpty() ? "" : " - " + item.why()));
        }
        if (!result.signer().isEmpty()) {
            out.println("this machine's line, to take out of each project's machine-signers where it is listed:");
            out.println("            " + result.signer());
        }
        if (askYes && !result.refused()) {
            out.println("nothing was removed; run it again with --yes to clear what is listed");
        }
        out.flush();
        return failed ? 1 : 0;
    }
}
