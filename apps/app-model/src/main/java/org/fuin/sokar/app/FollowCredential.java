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
 * those paths would have used. Found through the interface's dialog.
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

    /** Where a wrong passphrase is said, when a command asks for a shut vault. */
    private static final java.io.PrintWriter STDERR = new java.io.PrintWriter(
            new java.io.OutputStreamWriter(System.err, java.nio.charset.StandardCharsets.UTF_8), true);


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

    /** Whether anything was lent, rather than only the destination's policy given. */
    private final boolean lending;

    private FollowCredential(final Map<String, String> environment,
            final @Nullable SshAgentServer agent, final @Nullable Path socket,
            final boolean vaultShut) {
        this(environment, agent, socket, vaultShut, !environment.isEmpty());
    }

    private FollowCredential(final Map<String, String> environment,
            final @Nullable SshAgentServer agent, final @Nullable Path socket,
            final boolean vaultShut, final boolean lending) {
        this.environment = environment;
        this.agent = agent;
        this.socket = socket;
        this.vaultShut = vaultShut;
        this.lending = lending;
    }

    /**
     * Gives a destination its policy and lends nothing.
     *
     * @param context The machine.
     * @param url The destination.
     * @param vaultShut Whether the vault exists and is shut.
     * @return The lease.
     */
    private static FollowCredential nothingLent(final SokarContext context, final String url,
            final boolean vaultShut) {
        return new FollowCredential(policyFor(context, url), null, null, vaultShut, false);
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
     * Returns the vault entry holding the ssh key that reaches a URL, as {@link #open} would lend it: the credential
     * declared for it, else the first of the names the address implies that the vault holds.
     *
     * @param context The machine.
     * @param url An ssh address.
     * @return The entry's name, or {@code null} when the address takes no key or no key for it is held.
     */
    public static @Nullable String sshKeyFor(final SokarContext context, final String url) {
        if (GitCredentialNames.kindOf(url) != GitCredentialNames.Kind.KEY) {
            return null;
        }
        final Credential declared = context.credentialRegistry().forUrl("git", url);
        if (declared != null) {
            return declared.kind() == Credential.Kind.SSH_KEY && declared.source() == Credential.Source.VAULT
                    ? declared.id() : null;
        }
        final Map<String, org.fuin.sokar.vault.VaultEntry> held =
                context.readableCredentials().orElseGet(Map::of);
        return GitCredentialNames.candidatesFor(url).stream().filter(held::containsKey).findFirst().orElse(null);
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
        return open(context, url, label, purpose, null);
    }

    /**
     * Lends a credential that has not been recorded yet.
     *
     * @param context The machine.
     * @param url Where it is about to connect.
     * @param label Names the socket.
     * @param purpose What the caller is doing.
     * @param offered A credential to use INSTEAD of whatever is recorded, or {@code null}. What
     *        makes it possible to ask a host about a key somebody is only considering - asking
     *        with the recorded credential instead would answer about a different key, or about
     *        none, and say nothing about the one in front of them.
     * @return The lease.
     */
    static FollowCredential open(final SokarContext context, final String url,
            final String label, final String purpose, final @Nullable Credential offered) {

        if (offered != null) {
            return lend(context, offered, label);
        }
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
        // Nothing lent still needs the destination's policy: an ssh origin reached with the developer's own agent
        // key is checked against this machine's known hosts, strictly, or ssh refuses a host this machine vouched for.
        // At a terminal a shut vault is opened here for this command; the daemon has none and waits as before.
        context.openIfShut(true, false, STDERR);
        if (context.vault().exists() && context.opener().isEmpty()) {
            return nothingLent(context, url, true);
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
        return nothingLent(context, url, false);
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
                    "GIT_SSH_COMMAND", sshCommand(context, null)),
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
                    "GIT_SSH_COMMAND", sshCommand(context, credential.id())),
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
        // At a terminal a shut vault is opened here for this command; the daemon has none and waits as before.
        context.openIfShut(true, false, STDERR);
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
        final org.fuin.sokar.vault.AgentKey key;
        try {
            // Whatever shape the entry is - an Ed25519 seed from before, or a key file.
            key = org.fuin.sokar.vault.StoredKey.of(held.value(), credential.id());
        } catch (final VaultException | IllegalArgumentException ex) {
            // There and unusable, which is not the same as absent and must not be reported as it.
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
                    "GIT_SSH_COMMAND", sshCommand(context, null)),
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
                "credential.https://" + host + ".helper=" + word(SokarBinary.path().toString())
                        + " vault credential " + option + " " + word(where)
                        + (userOf(credential) == null ? ""
                                : " --username " + word(String.valueOf(userOf(credential))))),
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
     * Returns what git needs, including the host-key policy when nothing was lent.
     *
     * @param context The machine.
     * @param url Where git is about to connect.
     * @return The variables.
     */
    Map<String, String> environmentFor(final SokarContext context, final String url) {
        final Map<String, String> copy =
                new java.util.LinkedHashMap<>(FollowCredential.policyFor(context, url));
        copy.putAll(environment());
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
        return lending;
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
     * Returns what git should run instead of plain ssh.
     * <p>
     * <strong>This belongs to the destination, not to the credential.</strong> It used to be built
     * only where a key was lent, so a machine with nothing to lend ran git with no policy at all -
     * and ssh went looking for {@code ssh-askpass} in a daemon, failed on the host key, and the
     * answer blamed the vault. An ssh destination needs the policy whether or not anything can be
     * lent to it.
     * <p>
     * <strong>Strict, never accept-new.</strong> A host nobody has vouched for stops the work and
     * is answered as a question - see {@link HostKeys}.
     *
     * @param context The machine.
     * @param identityFile A key file to name, or {@code null}.
     * @return The command for {@code GIT_SSH_COMMAND}.
     */
    static String sshCommand(final SokarContext context, final @Nullable String identityFile) {
        // Each path one shell word: git runs this through a shell, so a state directory with a space broke every
        // fetch, and a key path with ';' ran as shell.
        return "ssh -o StrictHostKeyChecking=yes"
                + " -o UserKnownHostsFile=" + word(knownHosts(context).toString())
                + (identityFile == null ? ""
                        : " -i " + word(identityFile) + " -o IdentitiesOnly=yes");
    }

    /** One shell word, whatever it holds. */
    private static String word(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Returns the environment for reaching a URL, whether or not anything is lent.
     *
     * @param context The machine.
     * @param url Where git is about to connect.
     * @return The variables, empty when the URL needs no ssh.
     */
    static Map<String, String> policyFor(final SokarContext context, final String url) {
        return GitCredentialNames.kindOf(url) == GitCredentialNames.Kind.KEY
                ? Map.of("GIT_SSH_COMMAND", sshCommand(context, null)) : Map.of();
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

    /**
     * Returns where host keys are remembered, making the directory if it is not there.
     *
     * @param context The machine.
     * @return The file.
     */
    static Path knownHostsFile(final SokarContext context) {
        return knownHosts(context);
    }

    private static Path knownHosts(final SokarContext context) {
        // state() is ALREADY '<XDG_STATE_HOME>/sokar', so resolving 'sokar' onto it wrote
        // '.../state/sokar/sokar/known_hosts' - a directory nothing creates, so ssh warned
        // "Failed to add the host to the list of known hosts" on every single fetch and learned
        // nothing from one fetch to the next. Found by an interface that read the warning
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
