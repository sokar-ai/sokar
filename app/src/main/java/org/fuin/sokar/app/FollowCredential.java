package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.credential.Credential;
import org.fuin.sokar.core.credential.CredentialRegistry;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshAgentServer;
import org.fuin.sokar.vault.VaultException;
import org.jspecify.annotations.Nullable;

/**
 * What a git command run on this host needs to reach a private repository, lent for its length.
 * <p>
 * <strong>Why this exists.</strong> Every git command this host runs against a real remote - the
 * fetch that follows a project, the fetch that measures how far a mirror is behind, and the push
 * that forwards an approved change - ran in a plain environment: no agent, no token, nothing from
 * the vault. A private repository was therefore reachable exactly as far as the account's own ssh
 * setup reached, while the refusal said <em>"this account's vault is shut, so the credential a
 * private repository needs is out of reach"</em> - pointing at a vault that held nothing any of
 * those paths would have used. Found by the operator, through Agent Frontend's dialog (QF39).
 * <p>
 * <strong>Where the work comes from does not change the credential.</strong> An agent in a
 * container, a person at {@code gate approve}, the daemon reconciling on a timer: the connection
 * is made here, by this host, so it is answered here, once.
 * <p>
 * <strong>An ssh key never leaves the vault.</strong> An agent is served in this process over a
 * socket in the runtime directory and closed when the command is done, exactly as a task gets one:
 * git asks for a signature and never holds anything it could keep. <strong>A token has to</strong>
 * - a bearer token is the secret that travels - so it goes to git through git's own credential
 * helper protocol over a pipe, never as an argument, and read-only is enough to follow.
 */
public final class FollowCredential implements AutoCloseable {

    /**
     * Returns what to type to store what this URL needs.
     *
     * @param url The repository.
     * @return The command, naming the host and the kind.
     */
    public static String storeCommand(final String url) {
        return GitCredentialNames.storeCommandFor(url);
    }

    private final Map<String, String> environment;

    private final @Nullable SshAgentServer agent;

    private final @Nullable Path socket;

    private final boolean vaultShut;

    private FollowCredential(final Map<String, String> environment,
            final @Nullable SshAgentServer agent, final @Nullable Path socket,
            final boolean vaultShut) {
        this.environment = environment;
        this.agent = agent;
        this.socket = socket;
        this.vaultShut = vaultShut;
    }

    /**
     * Lends what a command needs to reach a URL, for the length of that command.
     *
     * @param context The machine.
     * @param url Where it is about to connect.
     * @param label Names the socket, so two commands cannot collide.
     * @return Something to close. It may hold nothing at all, which is the ordinary case for a
     *         public destination and is never an error here - the command decides that.
     */
    static FollowCredential open(final SokarContext context, final String url,
            final String label) {
        return open(context, url, label, "git");
    }

    /**
     * Lends what a command needs to reach a URL, for a stated purpose.
     *
     * @param context The machine.
     * @param url Where it is about to connect.
     * @param label Names the socket.
     * @param purpose What the caller is doing, such as {@code git}. A token that may push packages
     *        has no business authenticating a clone unless somebody said so.
     * @return Something to close.
     */
    static FollowCredential open(final SokarContext context, final String url,
            final String label, final String purpose) {

        final Credential declared = context.credentialRegistry().forUrl(purpose, url);
        if (declared != null) {
            return lend(context, declared, label);
        }
        // Nothing declared: the names a git URL implies, which is what a machine that has never
        // written a registry has always used. Kept so that what somebody already stored is still
        // found, and so a machine needs no configuration for the ordinary case.
        final GitCredentialNames.Kind kind = GitCredentialNames.kindOf(url);
        if (kind == GitCredentialNames.Kind.NONE) {
            return new FollowCredential(Map.of(), null, null, false);
        }
        if (context.vault().exists() && context.opener().isEmpty()) {
            return new FollowCredential(Map.of(), null, null, true);
        }
        final Map<String, org.fuin.sokar.vault.VaultEntry> held =
                context.readableCredentials().orElseGet(Map::of);
        for (final String candidate : GitCredentialNames.candidatesFor(url)) {
            if (held.containsKey(candidate)) {
                return lend(context, new Credential(candidate,
                        kind == GitCredentialNames.Kind.KEY
                                ? Credential.Kind.SSH_KEY : Credential.Kind.TOKEN,
                        CredentialRegistry.normalise(url), null, purpose,
                        Credential.Source.VAULT), label);
            }
        }
        return new FollowCredential(Map.of(), null, null, false);
    }

    /**
     * Lends one declared credential.
     *
     * @param context The machine.
     * @param credential What to lend.
     * @param label Names the socket.
     * @return The lease.
     */
    private static FollowCredential lend(final SokarContext context, final Credential credential,
            final String label) {
        return switch (credential.source()) {
            case AGENT -> new FollowCredential(Map.of(
                    // Nothing is read and nothing is lent: the command inherits the account's own
                    // agent, and whoever added keys to it decides. The plainest answer to "use my
                    // own keys", and the one that keeps no copy anywhere.
                    "GIT_SSH_COMMAND", "ssh -o StrictHostKeyChecking=accept-new"
                            + " -o UserKnownHostsFile=" + knownHosts(context)),
                    null, null, false);
            case FILE -> fromFile(context, credential);
            case ENVIRONMENT -> fromEnvironment(context, credential);
            case VAULT -> fromVault(context, credential, label);
        };
    }

    private static FollowCredential fromFile(final SokarContext context,
            final Credential credential) {
        if (!Files.isReadable(Path.of(credential.id()))) {
            return new FollowCredential(Map.of(), null, null, false);
        }
        if (credential.kind() == Credential.Kind.SSH_KEY) {
            // Named to ssh rather than read here. A key in somebody's own ~/.ssh is theirs: this
            // does not copy it, does not parse it, and does not care what algorithm it is -
            // IdentitiesOnly so that the one they named is the one offered.
            return new FollowCredential(Map.of(
                    "GIT_SSH_COMMAND", "ssh -i " + credential.id() + " -o IdentitiesOnly=yes"
                            + " -o StrictHostKeyChecking=accept-new"
                            + " -o UserKnownHostsFile=" + knownHosts(context)),
                    null, null, false);
        }
        return helper(context, credential, "--file", credential.id());
    }

    private static FollowCredential fromEnvironment(final SokarContext context,
            final Credential credential) {
        if (System.getenv(credential.id()) == null) {
            return new FollowCredential(Map.of(), null, null, false);
        }
        if (credential.kind() == Credential.Kind.SSH_KEY) {
            // A key in a variable is not a thing ssh can be pointed at, and writing it to a file
            // to make one would be this creating the copy somebody asked it not to make.
            return new FollowCredential(Map.of(), null, null, false);
        }
        return helper(context, credential, "--env", credential.id());
    }

    private static FollowCredential fromVault(final SokarContext context,
            final Credential credential, final String label) {
        if (context.vault().exists() && context.opener().isEmpty()) {
            return new FollowCredential(Map.of(), null, null, true);
        }
        final org.fuin.sokar.vault.VaultEntry held =
                context.readableCredentials().orElseGet(Map::of).get(credential.id());
        if (held == null) {
            return new FollowCredential(Map.of(), null, null, false);
        }
        if (credential.kind() != Credential.Kind.SSH_KEY) {
            return helper(context, credential, "--entry", credential.id());
        }
        final SigningKey key;
        try {
            key = new SigningKey(
                    java.util.Base64.getDecoder().decode(held.value()), credential.id());
        } catch (final VaultException | IllegalArgumentException ex) {
            // Stored in a form nothing can sign with. 'vault put' converts a key file now, so
            // this is an old entry; the command will fail and say what it could not reach.
            return new FollowCredential(Map.of(), null, null, false);
        }
        try {
            final Path where = socketFor(context, label);
            Files.createDirectories(where.getParent());
            Files.deleteIfExists(where);
            final SshAgentServer serving = new SshAgentServer(where, List.of(key));
            Thread.ofVirtual().start(serving);
            return new FollowCredential(Map.of(
                    "SSH_AUTH_SOCK", where.toString(),
                    // The host key of a forge nobody has talked to yet cannot be known in advance,
                    // and a prompt here hangs a daemon nobody is watching. Accepted on first use
                    // and written down, which is what the task path does for the same reason.
                    "GIT_SSH_COMMAND", "ssh -o StrictHostKeyChecking=accept-new"
                            + " -o UserKnownHostsFile=" + knownHosts(context)),
                    serving, where, false);
        } catch (final IOException | RuntimeException ex) {
            return new FollowCredential(Map.of(), null, null, false);
        }
    }

    /**
     * Points git at the helper that answers its credential protocol.
     * <p>
     * What travels as an argument is where the value lives, never the value:
     * {@code /proc/<pid>/cmdline} is world readable on both supported distributions.
     */
    private static FollowCredential helper(final SokarContext context,
            final Credential credential, final String option, final String where) {
        final String host = GitCredentialNames.hostOf(credential.match());
        if (host == null) {
            return new FollowCredential(Map.of(), null, null, false);
        }
        return new FollowCredential(Map.of("SOKAR_GIT_CREDENTIAL_CONFIG",
                "credential.https://" + host + ".helper=" + SokarBinary.path()
                        + " vault credential " + option + " " + where
                        + (userOf(credential) == null ? ""
                                : " --username " + userOf(credential))),
                null, null, false);
    }

    /**
     * Returns what to add to git's environment.
     *
     * @return The variables, empty when there is no key to lend.
     */
    Map<String, String> environment() {
        final Map<String, String> copy = new java.util.LinkedHashMap<>(environment);
        copy.remove("SOKAR_GIT_CREDENTIAL_CONFIG");
        return Map.copyOf(copy);
    }

    /**
     * Returns what has to go on git's command line before the verb, if anything.
     * <p>
     * Only {@code -c credential.<url>.helper=...}, which is configuration rather than a secret:
     * it names the vault entry, and the value is read by the helper git runs.
     *
     * @return The arguments, empty when none are needed.
     */
    List<String> arguments() {
        final String configured = environment.get("SOKAR_GIT_CREDENTIAL_CONFIG");
        return configured == null ? List.of() : List.of("-c", configured);
    }

    /**
     * Tells whether a key was actually lent.
     *
     * @return {@code true} when git was given an agent.
     */
    boolean lent() {
        return !environment.isEmpty();
    }

    /**
     * Tells whether the vault exists and is shut.
     *
     * @return {@code true} when unlocking is the thing that would help.
     */
    boolean vaultShut() {
        return vaultShut;
    }

    /**
     * Tells whether a URL is one this machine has a credential for at all.
     * <p>
     * A local path that cannot be reached is not explained by a missing credential, and saying so
     * would send somebody to store one that changes nothing.
     *
     * @param url What is being reached.
     * @return {@code true} for an ssh or https URL.
     */
    static boolean needsACredential(final String url) {
        return GitCredentialNames.kindOf(url) != GitCredentialNames.Kind.NONE;
    }

    /**
     * Returns the username to send, reading it from the environment when it names a variable.
     * <p>
     * One field and one rule: a {@code user} that begins with {@code $} is read from this
     * machine's environment, and anything else is the name itself. A username is not a secret, but
     * on a CI runner it arrives the same way the token does - and a second field for it would be
     * one more thing to keep in step.
     *
     * @param credential The record.
     * @return The username, or {@code null} when there is none or the variable is not set.
     */
    private static @Nullable String userOf(final Credential credential) {
        final String said = credential.user();
        if (said == null || said.isBlank()) {
            return null;
        }
        if (!said.startsWith("$")) {
            return said;
        }
        final String value = System.getenv(said.substring(1));
        return value == null || value.isBlank() ? null : value;
    }

    private static Path socketFor(final SokarContext context, final String label) {
        // In the runtime directory, because a unix socket path is short by nature and a state
        // directory nested under a home directory runs out of room at 108 characters.
        return context.paths().xdg().runtime().resolve("sokar").resolve("git-" + label + ".sock");
    }

    private static Path knownHosts(final SokarContext context) {
        // state() is ALREADY '<XDG_STATE_HOME>/sokar', so resolving 'sokar' onto it wrote
        // '.../state/sokar/sokar/known_hosts' - a directory nothing creates, so ssh warned
        // "Failed to add the host to the list of known hosts" on every single fetch and learned
        // nothing from one fetch to the next. Found by Agent Frontend, who read the warning
        // instead of skipping past it.
        final Path file = context.paths().xdg().state().resolve("known_hosts");
        try {
            Files.createDirectories(file.getParent());
        } catch (final IOException ex) {
            // ssh will say it could not write; that is its message to give, not ours to guess at.
            return file;
        }
        return file;
    }

    @Override
    public void close() {
        if (agent != null) {
            agent.close();
        }
        if (socket != null) {
            try {
                Files.deleteIfExists(socket);
            } catch (final IOException ex) {
                // A socket file left behind is tidied by the next follow, which deletes it before
                // binding. Failing a follow over it would be the wrong trade.
                return;
            }
        }
    }
}
