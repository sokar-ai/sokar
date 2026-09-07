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
 */
public record SokarContext(CommandRunner runner, SokarPaths paths,
        java.util.function.ToIntFunction<List<String>> exec) {

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
        return new SokarContext(new ProcessCommandRunner(), SokarPaths.current(),
                SokarContext::runInTerminal);
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
    public org.fuin.sokar.vault.VaultFile vault() {
        return new org.fuin.sokar.vault.VaultFile(paths.vaultFile());
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
                org.fuin.sokar.vault.KernelKeyring.source(paths.vaultKeyringKey()),
                new org.fuin.sokar.vault.ConsolePassphrase("Vault passphrase: ")).require();
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
        final java.util.Optional<char[]> passphrase = new org.fuin.sokar.vault.PassphraseTiers(
                org.fuin.sokar.vault.KernelKeyring.source(paths.vaultKeyringKey())).passphrase();
        if (passphrase.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(vault.read(passphrase.get()));
        } catch (org.fuin.sokar.vault.VaultException ex) {
            // A wrong passphrase or a damaged file. Unreadable rather than empty: the operator
            // has something to fix either way, and reporting it as empty hides it.
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
                paths.agentDirectory(), paths.xdg().runtime());
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
        return paths.providerDirectory().all();
    }

    /**
     * Returns a handle on the container runtime.
     *
     * @return Podman.
     */
    public org.fuin.sokar.runtime.Podman podman() {
        return new org.fuin.sokar.runtime.Podman(runner, "podman",
                paths.networkConfiguration());
    }

    /**
     * Returns the task runner.
     *
     * @return Runner.
     */
    public TaskRunner tasks() {
        return new TaskRunner(runner, paths);
    }
}
