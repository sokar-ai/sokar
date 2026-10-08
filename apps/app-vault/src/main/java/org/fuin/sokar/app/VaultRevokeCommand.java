package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.vault.Keyslot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Takes a device's way into the vault away.
 * <p>
 * One wrapped blob is deleted and nothing else happens: no re-keying, no other device disturbed, no
 * passphrase rotated. A lost laptop costs exactly this command.
 */
@Command(name = "revoke",
        mixinStandardHelpOptions = true,
        description = "Removes a device's way into the vault.")
public class VaultRevokeCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<id>",
            description = "The keyslot, as 'sokar vault devices' lists it.")
    private String id;

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
        final Keyslots.Revocation result = new Keyslots(context).revoke(id);
        if (result.outcome() != Keyslots.Revoked.REVOKED) {
            err.println("sokar: " + result.detail());
            if (result.outcome() == Keyslots.Revoked.VAULT_LOCKED) {
                err.println("       unlock it first: sokar vault unlock");
            }
            err.flush();
            return 1;
        }
        out.println("revoked " + id);
        out.println("still open the vault:");
        for (final Keyslot slot : result.remaining()) {
            out.println("   " + (slot.recovery() ? "the passphrase" : slot.name()));
        }
        out.flush();
        return 0;
    }
}
