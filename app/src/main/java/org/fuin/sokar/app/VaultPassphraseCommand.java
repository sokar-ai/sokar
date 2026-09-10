package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.runtime.ContainerSummary;
import org.fuin.sokar.vault.CommandPassphrase;
import org.fuin.sokar.vault.ConsolePassphrase;
import org.fuin.sokar.vault.KernelKeyring;
import org.fuin.sokar.vault.PassphraseTiers;
import org.fuin.sokar.vault.VaultException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Re-encrypts the vault under a different passphrase.
 * <p>
 * <strong>At the machine, never over the socket.</strong> The same rule as unlocking, and for the
 * same reason: this takes a passphrase, and the daemon has no terminal to take one at. A method
 * would mean a secret travelling INTO the contract, over a socket that can be forwarded across
 * ssh, where everything else only ever travels out as a name.
 * <p>
 * Until this existed the product could not change a passphrase at all - not over the wire and not
 * here - so the only way was to build a new vault and store every credential again, which needs
 * the values, which the vault deliberately will not give back.
 */
@Command(name = "passphrase",
        mixinStandardHelpOptions = true,
        description = "Re-encrypts the vault under a different passphrase.")
public class VaultPassphraseCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--passphrase-command", paramLabel = "<command>",
            description = "Command whose first output line is the CURRENT passphrase.")
    private String passphraseCommand;

    @Option(names = "--new-passphrase-command", paramLabel = "<command>",
            description = "Command whose first output line is the NEW passphrase. Without it, it"
                    + " is asked for twice.")
    private String newPassphraseCommand;

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
        final var vault = context.vault();

        if (!vault.exists()) {
            err.println("sokar: there is no vault at " + vault.path() + " to re-encrypt");
            err.flush();
            return 69;
        }

        final var runner = new ProcessCommandRunner();
        char[] current = null;
        char[] fresh = null;
        char[] again = null;
        try {
            current = new PassphraseTiers(new CommandPassphrase(runner, passphraseCommand),
                    new ConsolePassphrase("Current vault passphrase: ")).require();

            // Checked before anything else is asked for. Discovering the current one was wrong
            // after somebody has typed a new one twice wastes the part of this that is tedious.
            if (!vault.accepts(current)) {
                err.println("sokar: that passphrase does not open " + vault.path()
                        + " - nothing was changed");
                err.flush();
                return 70;
            }

            if (newPassphraseCommand != null) {
                fresh = new CommandPassphrase(runner, newPassphraseCommand).passphrase()
                        .orElseThrow(() -> new VaultException(
                                "the new-passphrase command produced nothing"));
            } else {
                fresh = new PassphraseTiers(
                        new ConsolePassphrase("New vault passphrase: ")).require();
                again = new PassphraseTiers(
                        new ConsolePassphrase("New vault passphrase again: ")).require();
                if (!Arrays.equals(fresh, again)) {
                    // Typed twice because there is nothing to compare it against afterwards: a
                    // mistyped new passphrase encrypts the vault under something nobody knows.
                    err.println("sokar: the two did not match - nothing was changed");
                    err.flush();
                    return 70;
                }
            }
            if (fresh.length == 0) {
                err.println("sokar: an empty passphrase would leave the vault unprotected"
                        + " - nothing was changed");
                err.flush();
                return 70;
            }

            vault.rekey(current, fresh);
            out.println("re-encrypted " + vault.path());

            // The cached one is now the WRONG one, so it goes. Not a courtesy: leaving it turns
            // the next command into a failure that reads like a damaged vault.
            if (KernelKeyring.available()) {
                final KernelKeyring.Forgotten forgotten =
                        new KernelKeyring(context.paths().vaultKeyringKey()).forget();
                if (forgotten == KernelKeyring.Forgotten.CLEARED) {
                    out.println("dropped    the cached passphrase;"
                            + " 'sokar vault unlock' with the new one");
                } else if (forgotten == KernelKeyring.Forgotten.UNKNOWN) {
                    // The cached passphrase is now the wrong one. Silence here would leave the
                    // next command failing in a way that reads like a damaged vault.
                    out.println("could not drop the cached passphrase, and it is now the OLD one;"
                            + " run 'sokar vault lock' until it succeeds");
                }
            }

            // Said every time it is true, for the same reason 'vault lock' says it: a proxy read
            // its credential when its task started and holds it in its own memory, where this
            // cannot reach. An operator changing a passphrase because one leaked has to know
            // that stopping those tasks is the part that ends it.
            final long running = context.podman().sokarTasks().stream()
                    .filter(ContainerSummary::running).count();
            if (running > 0) {
                out.println("running    " + running + " task" + (running == 1 ? "" : "s")
                        + " still hold the credential their proxy already read; stop them to end that");
            }
            out.flush();
            return 0;

        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        } finally {
            for (final char[] secret : new char[][] {current, fresh, again}) {
                if (secret != null) {
                    Arrays.fill(secret, '\0');
                }
            }
        }
    }
}
