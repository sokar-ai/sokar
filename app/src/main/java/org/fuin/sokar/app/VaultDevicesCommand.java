package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.vault.Keyslot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists what can open this vault, and says what each one is worth.
 * <p>
 * <strong>The worth is the point, not the list.</strong> A share kept in a keyring that unlocks at
 * login is worth less than one behind a security key, and the difference is invisible in a list of
 * device names. The product says that rather than implying it, and this is where it is
 * said - at the machine, without an interface.
 */
@Command(name = "devices",
        mixinStandardHelpOptions = true,
        description = "Lists what can open this vault, and what each is worth.")
public class VaultDevicesCommand implements Callable<Integer>, SokarFactory.ContextAware {

    /** What each kind of storage actually protects, in one line. */
    private static final java.util.Map<String, String> WORTH = java.util.Map.of(
            "USER_SCOPED", "any process running as you can ask for it",
            "APPLICATION_SCOPED", "only that application can ask for it",
            "FIDO2", "needs a touch on the key; same-user code cannot supply it",
            "TPM2", "sealed behind a PIN; same-user code cannot supply it");

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (!context.vault().exists()) {
            err.println("sokar: there is no vault at " + context.vault().path());
            err.flush();
            return 1;
        }
        final List<Keyslot> slots = new Keyslots(context).list();
        if (slots.isEmpty()) {
            err.println("sokar: " + context.vault().path() + " could not be read");
            err.flush();
            return 1;
        }
        out.printf("%-38s %-12s %-20s %s%n", "ID", "KIND", "NAME", "WHAT IT IS WORTH");
        for (final Keyslot slot : slots) {
            out.printf("%-38s %-12s %-20s %s%n", slot.id(),
                    slot.recovery() ? "passphrase" : "device", slot.name(),
                    slot.recovery() ? "typed by a person, stored nowhere"
                            : WORTH.getOrDefault(slot.storage(),
                                    slot.storage() + " - unknown to this Sokar"));
        }
        out.println();
        out.println("Remove one with: sokar vault revoke <id>");
        out.flush();
        return 0;
    }
}
