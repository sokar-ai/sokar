package org.fuin.sokar.vault;

import java.util.Optional;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandException;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * Takes the passphrase from the first line a command prints.
 * <p>
 * This one tier covers {@code pass}, Bitwarden, 1Password, {@code gopass} and every cloud secret
 * CLI, which is why it is worth more than any of the integrations it replaces. Sokar does not need
 * to know which one an operator uses.
 */
public class CommandPassphrase implements PassphraseSource {

    private final CommandRunner runner;

    @Nullable
    private final String command;

    /**
     * Constructor.
     *
     * @param runner Runs the command.
     * @param command Shell command, or {@code null} if none is configured.
     */
    public CommandPassphrase(CommandRunner runner, @Nullable String command) {
        this.runner = runner;
        this.command = command;
    }

    @Override
    public Optional<char[]> passphrase() {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        try {
            final CommandResult result = runner.run(Command.of("sh", "-c", command));
            if (!result.successful()) {
                // A configured command that fails is a real problem: falling through to the next
                // tier would prompt for a passphrase the operator has stored precisely so they do
                // not have to type it.
                throw new VaultException("The passphrase command failed with exit code "
                        + result.exitCode()
                        + (result.standardError().isBlank() ? "" : ": " + result.standardError().strip()));
            }
            final String first = result.standardOutput().lines().findFirst().orElse("");
            return first.isEmpty() ? Optional.empty() : Optional.of(first.toCharArray());
        } catch (CommandException ex) {
            throw new VaultException("Cannot run the passphrase command", ex);
        }
    }

    @Override
    public String name() {
        return "passphrase-command";
    }
}
