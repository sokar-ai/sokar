package org.fuin.sokar.app;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import org.fuin.sokar.agent.api.Credential;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultException;
import org.jspecify.annotations.Nullable;

/**
 * Copies a credential an agent already holds on this node into the vault.
 * <p>
 * <strong>Nothing secret moves anywhere.</strong> The value is read from a file on the node's own
 * disk and written to the node's own vault. That is what makes this usable from an interface while
 * storing one by hand is not: the caller names an agent, and nothing else crosses.
 * <p>
 * <strong>One implementation, two callers.</strong> The CLI and the daemon differ in exactly one
 * thing - where a passphrase comes from - so that is a parameter and the rest is shared. Two
 * implementations of "which key does this go under" would eventually disagree, and the way they
 * would disagree is by storing a credential where nothing looks for it, which reads as a missing
 * credential rather than as a bug.
 */
public final class CredentialImport {

    /** What happened, or why nothing did. */
    public enum Outcome {

        /** Copied into the vault. */
        IMPORTED,

        /** No agent of that name, or none given and more than one installed. */
        NO_SUCH_AGENT,

        /** The agent does not say where it keeps its credentials. */
        NO_CONFIG_DIRECTORY,

        /** The agent is installed but has not been logged in on this node. */
        NOTHING_TO_IMPORT,

        /** The vault cannot be opened without a passphrase nobody can be asked for here. */
        VAULT_LOCKED,

        /** The vault could not be written. */
        FAILED
    }

    /**
     * What an import did.
     *
     * @param outcome What happened.
     * @param name The vault key it was stored under, or "" when nothing was stored.
     * @param type The kind of credential, or "".
     * @param length How many characters arrived, so a caller can show that something did without
     *        showing what. Zero when nothing was stored.
     * @param source Where it was read from, or "".
     * @param detail Why it failed, or a warning about what was stored. "" when there is nothing
     *        to say.
     */
    public record Result(Outcome outcome, String name, String type, int length, String source,
            String detail) { }

    private CredentialImport() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static Result failed(Outcome outcome, String detail) {
        return new Result(outcome, "", "", 0, "", detail);
    }

    /**
     * Copies an agent's credential into the vault.
     *
     * @param context The machine.
     * @param agentName Which agent, or {@code null} for the only one installed.
     * @param configDirectory Where it keeps its credentials, or {@code null} for what it declares.
     * @param passphrase Supplies the vault passphrase, or nothing when it cannot be had.
     * @return What happened.
     */
    public static Result run(SokarContext context, @Nullable String agentName,
            @Nullable String configDirectory,
            Supplier<Optional<org.fuin.sokar.vault.VaultFile.Opener>> opener) {

        try (var agents = context.agents()) {

            final Optional<InstalledAgent> found = agentName != null ? agents.find(agentName)
                    : agents.names().size() == 1 ? agents.find(agents.names().getFirst())
                            : Optional.empty();
            if (found.isEmpty()) {
                return failed(Outcome.NO_SUCH_AGENT,
                        "no such agent; installed: " + String.join(", ", agents.names()));
            }
            final InstalledAgent agent = found.get();

            final String declared = configDirectory != null
                    ? configDirectory : agent.definition().configDirectory();
            if (declared == null) {
                return failed(Outcome.NO_CONFIG_DIRECTORY, "'" + agent.name() + "' does not say"
                        + " where it keeps its credentials");
            }

            final Path directory = VaultImportCommand.expand(declared);
            final Optional<Credential> credential = agent.extractCredential(directory);
            if (credential.isEmpty()) {
                return failed(Outcome.NOTHING_TO_IMPORT, "nothing to import from " + directory
                        + " - log in with the agent on this node first");
            }
            final Credential value = credential.get();

            // Stored under the provider's name: the credential is the provider's, and a second
            // agent reaching the same one must find it rather than store its own copy.
            final SelectedProvider selection =
                    SelectedProvider.choose(context.providers(), agent.definition(), null);
            final String key = selection == null ? agent.name() : selection.name();

            // Whatever opens the vault, not the passphrase in particular: a machine a device
            // unlocked stores a credential exactly as one a person unlocked does.
            final Optional<org.fuin.sokar.vault.VaultFile.Opener> way = opener.get();
            if (way.isEmpty()) {
                return failed(Outcome.VAULT_LOCKED, "the vault is locked, and nothing here can"
                        + " ask for a passphrase - unlock it at the machine, or with a device");
            }

            context.vault().update(way.get(), entries -> {
                LoginCredentials.put(entries, key, value, false);
                return entries;
            });

            // The same check 'vault put' makes. An agent's own file is the likelier source of a
            // real credential, but a half-written or logged-out one still reads as a success.
            final StringBuilder note = new StringBuilder();
            final String suspicious = new VaultEntry(value.secret(), value.type()).suspicious();
            if (suspicious != null) {
                note.append("check what was imported - ").append(suspicious);
            }
            final String unbrokerable = selection == null ? null
                    : selection.route().unbrokerableReason(value.type());
            if (unbrokerable != null) {
                note.append(note.isEmpty() ? "" : "; ")
                        .append("stored, but a task will refuse it: ").append(unbrokerable);
            }

            return new Result(Outcome.IMPORTED, key, value.type(), value.secret().length(),
                    directory.toString(), note.toString());

        } catch (VaultException ex) {
            return failed(Outcome.FAILED, CliErrors.reason(ex));
        } catch (RuntimeException ex) {
            return failed(Outcome.FAILED, CliErrors.reason(ex));
        }
    }
}
