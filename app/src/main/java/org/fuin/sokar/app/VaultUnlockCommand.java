package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.vault.CommandPassphrase;
import org.fuin.sokar.vault.ConsolePassphrase;
import org.fuin.sokar.vault.KernelKeyring;
import org.fuin.sokar.vault.PassphraseTiers;
import org.fuin.sokar.vault.SystemdCredential;
import org.fuin.sokar.vault.VaultException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Caches the vault passphrase in the kernel keyring for the rest of the session.
 * <p>
 * Without this an operator answers a passphrase prompt on every command. With it they answer once
 * per login, and the answer lives in the kernel rather than in any file - so it is gone at logout
 * and never survives a reboot.
 */
@Command(name = "unlock",
        mixinStandardHelpOptions = true,
        description = "Caches the vault passphrase in the kernel keyring for this session.")
public class VaultUnlockCommand implements Callable<Integer> {

    /** Keyring description under which the passphrase is cached. */
    public static final String KEY = "sokar:vault";

    @Option(names = "--forget", description = "Removes the cached passphrase instead.")
    private boolean forget;

    @Option(names = "--passphrase-command", paramLabel = "<command>",
            description = "Command whose first output line is the passphrase.")
    private String passphraseCommand;

    @Option(names = "--systemd-credential", paramLabel = "<file>",
            description = "systemd-creds encrypted file holding the passphrase.")
    private String systemdCredential;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        if (!KernelKeyring.available()) {
            err.println("sokar: libkeyutils is not available, so the passphrase cannot be cached");
            err.flush();
            return 69;
        }

        final KernelKeyring keyring = new KernelKeyring(KEY);

        if (forget) {
            out.println(keyring.forget() ? "forgotten" : "nothing was cached");
            out.flush();
            return 0;
        }

        final var runner = new ProcessCommandRunner();
        final PassphraseTiers tiers = new PassphraseTiers(
                // Strongest first: a TPM-sealed credential beats a password manager, which beats
                // typing. The keyring itself is not in this list - it is what is being filled.
                new SystemdCredential(runner, systemdCredential),
                new CommandPassphrase(runner, passphraseCommand),
                new ConsolePassphrase("Vault passphrase: "));

        try {
            keyring.store(tiers.require());
            out.println("cached in the kernel keyring for this session");
            out.flush();
            return 0;
        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
