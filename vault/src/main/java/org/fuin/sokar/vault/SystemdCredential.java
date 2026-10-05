package org.fuin.sokar.vault;

import java.util.Optional;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandException;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * Reads the passphrase from a {@code systemd-creds} encrypted file.
 * <p>
 * The strongest tier where it is available: on a machine with a TPM,
 * {@code systemd-creds encrypt} seals the passphrase to the hardware, so the encrypted file is
 * useless on any other machine and useless after the boot state changes. Nothing Sokar does can
 * match that, which is why this tier is tried before the others.
 */
public class SystemdCredential implements PassphraseSource {

    private final CommandRunner runner;

    @Nullable
    private final String file;

    /**
     * Constructor.
     *
     * @param runner Runs {@code systemd-creds}.
     * @param file Path of the encrypted credential, or {@code null} if none is configured.
     */
    public SystemdCredential(CommandRunner runner, @Nullable String file) {
        this.runner = runner;
        this.file = file;
    }

    @Override
    public Optional<char[]> passphrase() {

        if (file == null || file.isBlank()) {
            return Optional.empty();
        }
        try {
            final CommandResult result =
                    runner.run(Command.of("systemd-creds", "decrypt", file, "-"));
            if (!result.successful()) {
                // A configured credential that will not decrypt is a real problem: falling through
                // would prompt for a passphrase the operator sealed precisely so they would not
                // have to type it.
                throw new VaultException("systemd-creds could not decrypt " + file
                        + (result.standardError().isBlank() ? ""
                                : ": " + result.standardError().strip()));
            }
            final String value = result.standardOutput().strip();
            return value.isEmpty() ? Optional.empty() : Optional.of(value.toCharArray());
        } catch (CommandException ex) {
            // systemd-creds is absent on a non-systemd host. That is not a misconfiguration.
            return Optional.empty();
        }
    }

    /**
     * Encrypts a passphrase into a credential file.
     *
     * @param plaintext What to seal.
     * @param target Where to write it.
     * @param name Credential name, which systemd binds the ciphertext to.
     * @throws VaultException If sealing fails.
     */
    public void seal(char[] plaintext, String target, String name) {
        try {
            runner.runOrFail(Command.of("systemd-creds", "encrypt",
                            "--name=" + name, "-", target)
                    .withInput(new String(plaintext)));
        } catch (CommandException ex) {
            throw new VaultException("systemd-creds could not seal the passphrase: "
                    + ex.getMessage(), ex);
        }
    }

    @Override
    public String name() {
        return "systemd-creds";
    }
}
