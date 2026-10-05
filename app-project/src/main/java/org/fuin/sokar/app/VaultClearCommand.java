package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Clears the vault in one step: its file, backup and lock, what the keyring caches of it, and what the transports
 * keep with its secrets. Lists it first; {@code --yes} clears it.
 * <p>
 * Here, with the other clearings, and put into the {@code vault} group where the command line is assembled: it
 * reaches the transports and the tasks, which the vault's own area does not depend on.
 */
@Command(name = "clear",
        mixinStandardHelpOptions = true,
        description = "Clears the vault: its file, backup and lock, the cached passphrase and shares, and the accounts"
                + " a transport keeps with it. Lists it first; --yes clears it.")
public class VaultClearCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--yes", description = "Clears what the listing names. Without it, nothing is removed.")
    private boolean yes;

    @Option(names = "--dry-run", description = "Lists what would go, and removes nothing.")
    private boolean dryRun;

    @Option(names = "--force",
            description = "Clears a locked vault unread, or one whose transport could not clear a project's account."
                    + " A running task that holds a token from it still has to be stopped first.")
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
        final PrintWriter out = spec.commandLine().getOut();
        final Clearing.Result result = new VaultClearing(context).clear(listOnly, force);
        final int code = ClearCommand.render(result, listOnly, !dryRun && !yes, out);
        if (result.items().stream().anyMatch(item -> "vault".equals(item.kind())
                && item.status() == Clearing.Status.REMOVED)) {
            out.println("the vault is gone; 'sokar vault init' starts a new one");
            out.flush();
        }
        return code;
    }
}
