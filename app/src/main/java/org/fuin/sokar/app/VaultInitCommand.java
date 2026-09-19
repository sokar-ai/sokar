package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.vault.CommandPassphrase;
import org.fuin.sokar.vault.ConsolePassphrase;
import org.fuin.sokar.vault.PassphraseTiers;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultException;
import org.fuin.sokar.vault.VaultFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Creates an empty vault, with a passphrase typed twice.
 * <p>
 * <strong>Why this exists as its own verb.</strong> A vault used to come into being as a side
 * effect of storing the first credential, which asked for a passphrase <em>once</em>: a typo then
 * became the passphrase, and nothing existed to compare it against afterwards. Since the vault
 * format has no migration, the only cure for that is creating the vault again - so the mistake is
 * worth a whole command to prevent.
 * <p>
 * It is also the first step on a machine that has just been prepared: a device cannot be enrolled
 * into a vault that does not exist, and the daemon cannot make one because it has no terminal to
 * ask a passphrase at.
 */
@Command(name = "init",
        mixinStandardHelpOptions = true,
        description = "Creates an empty vault and sets its passphrase.")
public class VaultInitCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--passphrase-command", paramLabel = "<command>",
            description = "Reads the passphrase from this command instead of asking. Asked once,"
                    + " because a command cannot mistype.")
    private String passphraseCommand;

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
        final VaultFile vault = context.vault();

        if (vault.exists()) {
            // Never silently: a second init over an existing vault would replace a file holding
            // credentials nobody can get back.
            err.println("sokar: there is already a vault at " + vault.path());
            err.println("       change its passphrase with 'sokar vault passphrase', or move the"
                    + " file aside if you mean to start over");
            err.flush();
            return 70;
        }

        char[] chosen = null;
        char[] again = null;
        try {
            if (passphraseCommand != null) {
                chosen = new CommandPassphrase(new ProcessCommandRunner(), passphraseCommand)
                        .passphrase().orElseThrow(() -> new VaultException(
                                "the passphrase command produced nothing"));
            } else {
                chosen = new PassphraseTiers(
                        new ConsolePassphrase("New vault passphrase: ")).require();
                again = new PassphraseTiers(
                        new ConsolePassphrase("New vault passphrase again: ")).require();
                if (!Arrays.equals(chosen, again)) {
                    // Typed twice because there is nothing to compare it against afterwards, and
                    // because this format is not migrated: a mistyped passphrase means creating
                    // the vault again from nothing.
                    err.println("sokar: the two did not match - no vault was created");
                    err.flush();
                    return 70;
                }
            }
            if (chosen.length == 0) {
                err.println("sokar: an empty passphrase would leave the vault unprotected"
                        + " - no vault was created");
                err.flush();
                return 70;
            }

            vault.write(Map.<String, VaultEntry>of(), chosen);
            out.println("created " + vault.path());

            // Cached here, because the passphrase was just typed and just proved: it is the one
            // this command wrote the vault with, so there is nothing to verify it against and
            // nothing to get wrong. Without this, the very next 'vault put' answered "No
            // passphrase available, tried: kernel-keyring, prompt" - which reads as a broken
            // install rather than as "type it again", and is exactly what a wizard does next.
            final boolean cached = cache(chosen, err);
            out.println(cached
                    ? "unlocked  cached in this account's keyring, where the daemon finds it"
                    : "note      not cached - the next command will ask for it");
            out.println();
            out.println("It holds nothing yet. What usually comes next:");
            out.println("   sokar vault login <agent>          or 'import', or 'put'");
            out.println("   then enroll a device, which needs the vault open");
            out.flush();
            return 0;
        } catch (final VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        } finally {
            if (chosen != null) {
                Arrays.fill(chosen, '\0');
            }
            if (again != null) {
                Arrays.fill(again, '\0');
            }
        }
    }

    /**
     * Caches the passphrase this command just set.
     * <p>
     * Not being able to cache is not a reason to fail: the vault exists and is sound, and the
     * next command will ask. Said rather than swallowed, because "it asked me again" needs an
     * explanation the first time it happens.
     *
     * @param passphrase What was just used to write the vault.
     * @param err Where to explain a miss.
     * @return Whether it was cached.
     */
    private boolean cache(final char[] passphrase, final java.io.PrintWriter err) {
        if (!org.fuin.sokar.vault.KernelKeyring.available()) {
            err.println("sokar: libkeyutils is not available, so the passphrase cannot be cached");
            err.flush();
            return false;
        }
        try {
            new org.fuin.sokar.vault.KernelKeyring(context.paths().vaultKeyringKey())
                    .store(passphrase, null);
            return true;
        } catch (final VaultException ex) {
            err.println("sokar: the vault was created; its passphrase could not be cached: "
                    + ex.getMessage());
            err.flush();
            return false;
        }
    }
}
