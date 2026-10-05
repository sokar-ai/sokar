package org.fuin.sokar.app;

import java.util.List;
import java.util.function.Consumer;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.Exec;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.runtime.HookInstaller;

/**
 * Everything a command needs that touches the machine.
 * <p>
 * Exists so the commands can be tested. Without it a test of {@code sokar task run} builds an
 * image, starts a container and then replaces the test process with a shell - which is exactly
 * what happened before this was introduced.
 *
 * @param runner Runs external programs.
 * @param paths Where files go.
 * @param exec Runs a command on this process's terminal and waits for it.
 * @param environment Reads an environment variable; the operator's terminal is the only thing
 *        taken from it.
 * @param recognizesTasks Whether a container is a task only by the id Sokar recorded when it made it. Always so
 *        for {@link #real()}, which is how every command and the daemon build their context; a test that builds one
 *        itself says whether it wants it.
 */
public record SokarContext(CommandRunner runner, SokarPaths paths,
        java.util.function.ToIntFunction<List<String>> exec,
        java.util.function.UnaryOperator<String> environment, boolean recognizesTasks) {

    /**
     * Constructor for a context built by a test, whose containers are what its fake runtime says they are.
     *
     * @param runner Runs commands.
     * @param paths Where files go.
     * @param exec Runs a command on this process's terminal.
     * @param environment Reads an environment variable.
     */
    public SokarContext(CommandRunner runner, SokarPaths paths,
            java.util.function.ToIntFunction<List<String>> exec,
            java.util.function.UnaryOperator<String> environment) {
        this(runner, paths, exec, environment, false);
    }

    /**
     * Returns the id Sokar recorded when it created a task: from its saved record, which a reboot does not touch, or
     * from its runtime directory while the record is not saved yet.
     *
     * @param container The task's name.
     * @return The id, or {@code null} when Sokar recorded none.
     */
    public @org.jspecify.annotations.Nullable String recordedId(final String container) {
        final String saved = org.fuin.sokar.wire.Sidecar.recordedId(paths.tasks().taskRecord(container));
        return saved != null ? saved : org.fuin.sokar.wire.Sidecar.recordedId(paths.tasks().containerState(container));
    }

    /**
     * Returns this context recognizing a task only by the id Sokar recorded for it, as {@link #real()} always does.
     *
     * @return The context.
     */
    public SokarContext recognizingTasks() {
        return new SokarContext(runner, paths, exec, environment, true);
    }

    /**
     * Constructor for a context that reads no environment.
     * <p>
     * <strong>Empty rather than this process's, and that is the point.</strong> The only thing
     * read from the environment is the operator's terminal, and a test that picked up the
     * terminal of whoever ran it would pass on a developer's machine and assert something else
     * in CI - which is the failure this very change was made to stop having. Production builds
     * its context through {@link #real()}, which passes the real one.
     *
     * @param runner Runs commands.
     * @param paths Where files go.
     * @param exec Runs a command on this process's terminal.
     */
    public SokarContext(CommandRunner runner, SokarPaths paths,
            java.util.function.ToIntFunction<List<String>> exec) {
        this(runner, paths, exec, name -> null);
    }

    /**
     * Runs a command with this process's terminal and returns its exit code.
     * <p>
     * Waits rather than replacing this process. Replacing it left nothing behind to remove the
     * container afterwards, so every interactive task leaked one while promising the opposite.
     *
     * @param arguments Command and arguments.
     * @return Exit code, or 130 when interrupted.
     */
    private static int runInTerminal(List<String> arguments) {
        try {
            return new ProcessBuilder(arguments).inheritIO().start().waitFor();
        } catch (java.io.IOException ex) {
            throw new org.fuin.sokar.core.process.CommandException(
                    org.fuin.sokar.core.process.Command.of(arguments), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /**
     * Returns the context used by the shipped binary.
     *
     * @return Context talking to the real machine.
     */
    public static SokarContext real() {
        // Every container a command or the daemon acts on as a task is one Sokar recorded, never one named like it.
        return new SokarContext(new ProcessCommandRunner(), SokarPaths.current(),
                SokarContext::runInTerminal, System::getenv, true);
    }

    /**
     * Returns the hook installer for these paths.
     *
     * @return Installer.
     */
    public HookInstaller hooks() {
        return new HookInstaller(paths.hooksDirectory(), paths.binaryDirectory());
    }

    /**
     * Returns the vault file for these paths, whether or not it exists yet.
     *
     * @return The vault.
     */
    /**
     * Returns what this machine is configured to connect out with.
     * <p>
     * Read fresh each time: it is a small file, and a person who has just written one expects the
     * next command to use it.
     *
     * @return The registry, empty when there is no file or it cannot be read.
     */
    public org.fuin.sokar.core.credential.CredentialRegistry credentialRegistry() {
        try {
            return org.fuin.sokar.core.credential.CredentialRegistry.read(
                    paths.vault().credentialRegistry());
        } catch (final org.fuin.sokar.core.credential.CredentialException ex) {
            // A registry that cannot be read must not stop a public repository being reached.
            // What it would have said is said by whatever asked for a credential and did not get
            // one, and 'doctor' reports the file itself.
            return new org.fuin.sokar.core.credential.CredentialRegistry(java.util.List.of());
        }
    }

    /**
     * Returns the vault file.
     *
     * @return The vault.
     */
    public org.fuin.sokar.vault.VaultFile vault() {
        return new org.fuin.sokar.vault.VaultFile(paths.vault().vaultFile());
    }

    /**
     * Returns the vault passphrase, or fails saying what was tried.
     *
     * @return The passphrase.
     * @throws org.fuin.sokar.vault.VaultException If no tier produced one.
     */
    public char[] requirePassphrase() {
        final var runner = new ProcessCommandRunner();
        return new org.fuin.sokar.vault.PassphraseTiers(
                org.fuin.sokar.vault.KernelKeyring.source(paths.vault().vaultKeyringKey()),
                new org.fuin.sokar.vault.ConsolePassphrase("Vault passphrase: ")).require();
    }

    /**
     * Returns whatever can open this vault without asking anybody.
     * <p>
     * The passphrase first, because it is what most machines have; then a device's share, held in
     * the keyring by an unlock that has not run out. Both are ways in with a timeout, and which
     * one a machine has is not something a caller should have to know.
     *
     * @return An opener, or empty when the vault is shut.
     */
    public java.util.Optional<org.fuin.sokar.vault.VaultFile.Opener> opener() {
        final java.util.Optional<char[]> passphrase = new org.fuin.sokar.vault.PassphraseTiers(
                org.fuin.sokar.vault.KernelKeyring.source(paths.vault().vaultKeyringKey())).passphrase();
        if (passphrase.isPresent()) {
            return java.util.Optional.of(
                    org.fuin.sokar.vault.VaultFile.Opener.passphrase(passphrase.get()));
        }
        return VaultShare.held(paths).map(org.fuin.sokar.vault.VaultFile.Opener::share);
    }

    /**
     * Returns the credentials the vault holds, keyed by agent name.
     * <p>
     * Empty when there is no vault or it cannot be unlocked without asking. A task that needs no
     * credential must not be blocked by one that is merely absent.
     *
     * @return Credentials, possibly empty.
     */
    public java.util.Map<String, org.fuin.sokar.vault.VaultEntry> credentials() {
        return readableCredentials().orElseGet(java.util.Map::of);
    }

    /**
     * Returns the credentials the vault holds, or nothing when they cannot be read.
     * <p>
     * The distinction {@link #credentials()} cannot make. An empty map there means "there is no
     * vault", "it is locked" and "it cannot be decrypted" as well as "it is empty", and anything
     * reporting the store's state has to tell the last from the others: "we cannot tell you until
     * you unlock it" and "it is not there" are different sentences, and only one of them is
     * somebody's problem to fix before starting a task.
     * <p>
     * A vault that does not exist reads as an empty set rather than as unreadable. There is
     * nothing to unlock, and telling somebody to unlock it is the one instruction that cannot
     * help them.
     *
     * @return The entries, possibly empty, or empty when the vault is locked or unreadable.
     */
    public java.util.Optional<java.util.Map<String, org.fuin.sokar.vault.VaultEntry>>
            readableCredentials() {
        final org.fuin.sokar.vault.VaultFile vault = vault();
        if (!vault.exists()) {
            return java.util.Optional.of(java.util.Map.of());
        }
        final java.util.Optional<org.fuin.sokar.vault.VaultFile.Opener> opener = opener();
        if (opener.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            // A task's own tokens are not credentials: no listing and no choice may see them.
            return java.util.Optional.of(VaultNames.credentialsOnly(vault.read(opener.get())));
        } catch (org.fuin.sokar.vault.VaultException ex) {
            // A wrong passphrase or a damaged file. Unreadable rather than empty: the operator
            // has something to fix either way, and reporting it as empty hides it.
            return java.util.Optional.empty();
        }
    }

    /**
     * Returns the grants people have given, keyed by the entry they were given for, without their value
     * ever leaving this process: who granted each and when is what callers read.
     * <p>
     * Hidden from {@link #readableCredentials()}, as a task's own tokens are; this is the one view of them.
     *
     * @return The grants, or empty when the vault is locked or unreadable.
     */
    public java.util.Optional<java.util.Map<String, org.fuin.sokar.vault.VaultEntry>> readableGrants() {
        final org.fuin.sokar.vault.VaultFile vault = vault();
        if (!vault.exists()) {
            return java.util.Optional.of(java.util.Map.of());
        }
        final java.util.Optional<org.fuin.sokar.vault.VaultFile.Opener> opener = opener();
        if (opener.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            final java.util.Map<String, org.fuin.sokar.vault.VaultEntry> grants = new java.util.LinkedHashMap<>();
            vault.read(opener.get()).forEach((name, entry) -> {
                if (name.startsWith(VaultNames.GRANT_PREFIX)) {
                    grants.put(name.substring(VaultNames.GRANT_PREFIX.length()), entry);
                }
            });
            return java.util.Optional.of(grants);
        } catch (org.fuin.sokar.vault.VaultException ex) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Returns the agents installed on this machine, started and handshaken.
     * <p>
     * The caller closes it: each agent is a process, and leaving them running would leak one per
     * task.
     *
     * @return Installed agents.
     */
    public org.fuin.sokar.agent.api.InstalledAgents agents() {
        return new org.fuin.sokar.agent.api.InstalledAgents(
                paths.agents().agentDirectory(), paths.xdg().runtime());
    }

    /**
     * Returns the providers declared on this machine.
     * <p>
     * Read every time rather than cached: a provider is a file an operator can drop in, and
     * nothing should have to be restarted for it to be seen.
     *
     * @return Providers by name.
     */
    public java.util.Map<String, org.fuin.sokar.agent.api.ProviderDefinition> providers() {
        return paths.agents().providerDirectory().all();
    }

    /**
     * Returns a handle on the container runtime.
     *
     * @return Podman.
     */
    public org.fuin.sokar.runtime.Podman podman() {
        // Only the tasks Sokar recorded, each by the id it recorded: a container named like a task is not one.
        final org.fuin.sokar.runtime.Podman podman = new org.fuin.sokar.runtime.Podman(runner, "podman",
                paths.egress().networkConfiguration(), environment);
        return recognizesTasks
                ? podman.recognizing(this::recordedId)
                : podman;
    }

    /**
     * Returns the task runner.
     *
     * @return Runner.
     */
    public TaskRunner tasks() {
        return new TaskRunner(runner, paths, environment);
    }
}
