package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.vault.VaultException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Removes one credential from the vault.
 */
@Command(name = "remove",
        mixinStandardHelpOptions = true,
        description = "Removes a credential from the vault.")
public class VaultRemoveCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<name>", description = "Name of the entry to remove.")
    private String name;

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

        if (!context.vault().exists()) {
            err.println("sokar: no vault at " + context.vault().path());
            err.flush();
            return 69;
        }
        if (TaskSecrets.reserved(name)) {
            // A task's own token: removing it by hand would break that task's return after a reboot.
            err.println("sokar: '" + name + "' belongs to a task, not to you - removing the task removes it");
            err.flush();
            return 64;
        }

        final boolean[] removed = { false };
        try {
            context.vault().update(context.requirePassphrase(), entries -> {
                removed[0] = entries.remove(name) != null;
                return entries;
            });
        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }

        if (!removed[0]) {
            // Not an error: removing something that is not there leaves the wanted state.
            out.println("nothing named '" + name + "' was in the vault");
            out.flush();
            return 0;
        }
        out.println("removed   " + name);
        out.flush();
        return 0;
    }
}
