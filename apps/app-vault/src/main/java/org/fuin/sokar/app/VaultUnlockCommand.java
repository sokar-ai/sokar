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
import org.jspecify.annotations.Nullable;
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
        description = "Caches the vault passphrase in this account's kernel keyring, where the"
                + " daemon and later sessions find it.")
public class VaultUnlockCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--forget",
            description = "Removes the cached passphrase instead. The same as 'vault lock'.")
    private boolean forget;

    @Option(names = "--passphrase-command", paramLabel = "<command>",
            description = "Command whose first output line is the passphrase.")
    private @Nullable String passphraseCommand;

    @Option(names = "--systemd-credential", paramLabel = "<file>",
            description = "systemd-creds encrypted file holding the passphrase.")
    private @Nullable String systemdCredential;

    @Option(names = "--for", paramLabel = "<duration>",
            description = "How long to keep it: 45s, 30m, 8h. Without this it is kept until"
                    + " 'vault lock' or until your last session ends.")
    private @Nullable String keepFor;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Reads a duration written the way somebody types it.
     * <p>
     * Not ISO-8601: an operator bounding an unlock writes {@code 30m}, not {@code PT30M}, and a
     * flag nobody can use without reading the help is a flag nobody uses.
     *
     * @param text Value of {@code --for}, or {@code null}.
     * @return The duration, or {@code null} when none was given.
     * @throws IllegalArgumentException If it cannot be read, saying what was expected.
     */
    static java.time.@org.jspecify.annotations.Nullable Duration duration(
            @org.jspecify.annotations.Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final String said = text.strip().toLowerCase(java.util.Locale.ROOT);
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^(\\d+)([smh])$").matcher(said);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("cannot read '" + text + "' as a duration;"
                    + " write it as 45s, 30m or 8h");
        }
        final long amount = Long.parseLong(matcher.group(1));
        if (amount == 0) {
            // Zero would be a key the kernel discards at once, which reads as "unlock did
            // nothing" - and somebody who means that has 'vault lock'.
            throw new IllegalArgumentException("'--for 0" + matcher.group(2)
                    + "' would discard it immediately; use 'sokar vault lock' to not cache one");
        }
        return switch (matcher.group(2)) {
            case "s" -> java.time.Duration.ofSeconds(amount);
            case "m" -> java.time.Duration.ofMinutes(amount);
            default -> java.time.Duration.ofHours(amount);
        };
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        if (forget) {
            // One implementation, under both names: an operator who locks through the older flag
            // must be told the same thing about the tasks that are still running.
            return VaultLockCommand.lock(context, out);
        }

        if (!context.vault().exists() && asker != Offer.Asker.NOBODY && new Offer(asker, false, false, err).resolve(
                new Offer.Remedy("there is no vault at " + context.vault().path() + " to unlock",
                        "Make one now, as 'sokar vault init' does - it asks the passphrase twice and keeps it?",
                        "sokar vault init", "", false,
                        () -> context.exec().applyAsInt(java.util.List.of(SokarBinary.path(), "vault", "init")) == 0,
                        () -> context.vault().exists()))) {
            out.println("vault     made, and unlocked by 'sokar vault init'");
            out.flush();
            return 0;
        }
        if (!context.vault().exists()) {
            // Refused before anything is asked: a passphrase cached for a vault that does not exist
            // opens nothing, and the first 'vault put' then made the vault with it - typed once, so
            // a typo became the passphrase with nothing to compare it against. 'vault init' asks twice.
            err.println("sokar: there is no vault at " + context.vault().path() + " to unlock -"
                    + " create it with 'sokar vault init', which sets the passphrase and caches it;"
                    + " nothing was cached");
            err.flush();
            return 1;
        }

        if (!KernelKeyring.available()) {
            err.println("sokar: libkeyutils is not available, so the passphrase cannot be cached");
            err.flush();
            return 69;
        }

        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());

        final var runner = new ProcessCommandRunner();
        final PassphraseTiers tiers = new PassphraseTiers(
                // Strongest first: a TPM-sealed credential beats a password manager, which beats
                // typing. The keyring itself is not in this list - it is what is being filled.
                new SystemdCredential(runner, systemdCredential),
                new CommandPassphrase(runner, passphraseCommand),
                new ConsolePassphrase("Vault passphrase: "));

        try {
            final char[] passphrase = tiers.require();
            // Caching an unverified passphrase moves the failure to the next command, where it
            // reads as a possibly corrupt vault.
            final var vault = context.vault();
            if (!vault.accepts(passphrase)) {
                err.println("sokar: that passphrase does not open " + vault.path()
                        + " - nothing was cached");
                err.flush();
                return 70;
            }
            final java.time.Duration bound;
            try {
                bound = duration(keepFor);
            } catch (IllegalArgumentException ex) {
                err.println("sokar: " + ex.getMessage());
                err.flush();
                return 2;
            }
            keyring.store(passphrase, bound);
            out.println(bound == null
                    ? "cached in this account's kernel keyring - the daemon and later sessions"
                            + " find it, and it is gone at reboot"
                    : "cached in the kernel keyring; the kernel discards it in " + keepFor);
            out.flush();
            return 0;
        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }

    /** Who is asked, where a refusal would name the command: this process's terminal, or nobody without one. */
    Offer.Asker asker = Offer.atTerminal() ? Offer.Asker.terminal() : Offer.Asker.NOBODY;
}
