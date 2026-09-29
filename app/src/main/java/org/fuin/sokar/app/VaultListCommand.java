package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.vault.VaultException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists what the vault holds, without showing any of it.
 */
@Command(name = "list",
        mixinStandardHelpOptions = true,
        description = "Lists the names the vault holds. Never the values.")
public class VaultListCommand implements Callable<Integer>, SokarFactory.ContextAware {

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

        try {
            if (!context.vault().exists()) {
                out.println("no vault at " + context.vault().path());
                out.flush();
                return 0;
            }
            final var entries = TaskSecrets.credentialsOnly(context.vault().read(context.requirePassphrase()));
            if (entries.isEmpty()) {
                out.println("the vault is empty");
            } else {
                entries.forEach((name, entry) -> {
                    out.printf("%-20s %-10s %d characters%n",
                            name, entry.type() == null ? "-" : entry.type(), entry.value().length());
                    // Configuration, so shown whole: a token URL or a client id is not a secret.
                    entry.settings().forEach((setting, text) -> out.printf("%-20s %s = %s%n", "", setting, text));
                });
            }
            out.flush();
            return 0;
        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
