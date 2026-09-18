package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.fuin.sokar.core.config.XdgPaths;
import org.jspecify.annotations.Nullable;

/**
 * Where Sokar puts the files that belong to one task run.
 *
 * @param xdg The base directories.
 * @param binaryDirectory Directory the hook binaries are installed in.
 * @param packagedHooks Where a package would have put the hook binaries.
 * @param packagedAgents Where a package would have put the agent binaries.
 */
public record SokarPaths(XdgPaths xdg, Path binaryDirectory, Path packagedHooks,
        Path packagedAgents) {

    /**
     * Constructor for the real machine, where the packaged locations are fixed.
     *
     * @param xdg The base directories.
     * @param binaryDirectory Directory the hook binaries are installed in.
     */
    public SokarPaths(XdgPaths xdg, Path binaryDirectory) {
        this(xdg, binaryDirectory, PACKAGED_HOOKS, AgentDirectory.PACKAGED);
    }

    /** Where the sokar package puts the hook binaries. */
    static final Path PACKAGED_HOOKS = Path.of("/usr/libexec/sokar/hooks");

    /** Where the sokar package puts the SELinux policy source and its installer. */
    static final Path PACKAGED_SELINUX = Path.of("/usr/share/sokar/selinux");

    /** Name of a hook binary, used to tell an install apart from an empty directory. */
    private static final String MARKER = "sokar-hook-nft";

    /** What the program that decides a message's content is called. */
    public static final String MESSAGE_FILTER = "sokar-message-sluice-filter";

    /**
     * Returns the command that installs the SELinux policy module.
     *
     * @return Command an operator can paste.
     */
    public String selinuxInstaller() {
        return "sudo " + PACKAGED_SELINUX.resolve("install-selinux-policy.sh");
    }

    /**
     * Returns the paths for the current user.
     * <p>
     * The hook binaries come either from a package, which installs them system-wide, or from a
     * local build, which leaves them in {@code ~/.local/bin}. The operator's own copy wins, the
     * same order {@code AgentDirectory} uses for agents, so a developer can test a hook without
     * uninstalling the package.
     *
     * @return Paths.
     */
    public static SokarPaths current() {
        return new SokarPaths(XdgPaths.current(), hookBinaries(
                Path.of(System.getProperty("user.home"), ".local", "bin"), PACKAGED_HOOKS));
    }

    /**
     * Picks the first directory that actually holds the hooks.
     *
     * @param locations Directories to consider, the operator's own first.
     * @return The first directory containing a hook binary, or the first location if none does -
     *         so that an error message names the place a developer would look.
     */
    static Path hookBinaries(Path... locations) {
        for (final Path location : locations) {
            if (java.nio.file.Files.isRegularFile(location.resolve(MARKER))) {
                return location;
            }
        }
        return locations[0];
    }

    /**
     * Returns the packaged hook directory when it is installed but not used.
     *
     * @return The shadowed directory, or {@code null} when nothing is shadowed.
     */
    @Nullable
    public Path shadowedHookBinaries() {
        return shadowedHooks(binaryDirectory, packagedHooks);
    }

    /**
     * Returns the agent directories to scan, the operator's own first.
     *
     * @return Directory scanner.
     */
    public AgentDirectory agentDirectory() {
        return new AgentDirectory(java.util.List.of(xdg.data().resolve("agents"), packagedAgents));
    }

    /**
     * Returns the provider directories to scan, the operator's own first.
     * <p>
     * Data files rather than executables, so they live under {@code share} where an agent binary
     * lives under {@code libexec}.
     *
     * @return Directory scanner.
     */
    public org.fuin.sokar.agent.api.ProviderDirectory providerDirectory() {
        return new org.fuin.sokar.agent.api.ProviderDirectory(java.util.List.of(
                xdg.data().resolve("providers"),
                org.fuin.sokar.agent.api.ProviderDirectory.PACKAGED));
    }

    /**
     * Returns the curated egress sets to scan, the operator's own first.
     * <p>
     * Beside the providers and for the same reason: data an operator can add to without root and
     * without rebuilding anything.
     *
     * @return Directory scanner.
     */
    public org.fuin.sokar.shield.EgressSetDirectory egressSets() {
        return new org.fuin.sokar.shield.EgressSetDirectory(java.util.List.of(
                xdg.data().resolve("egress"),
                org.fuin.sokar.shield.EgressSetDirectory.PACKAGED));
    }

    /**
     * Returns the first location holding the hooks that is not the chosen one.
     *
     * @param chosen The directory actually in use.
     * @param locations Directories to consider.
     * @return A shadowed directory, or {@code null}.
     */
    @Nullable
    static Path shadowedHooks(Path chosen, Path... locations) {
        for (final Path location : locations) {
            if (!location.equals(chosen)
                    && java.nio.file.Files.isRegularFile(location.resolve(MARKER))) {
                return location;
            }
        }
        return null;
    }

    /** Environment variable naming a vault other than this user's own. */
    public static final String VAULT_VARIABLE = "SOKAR_VAULT";

    /**
     * Returns the vault this process should use.
     * <p>
     * Overridable because an acceptance run must not read or write the operator's own vault: the
     * data directory itself cannot be redirected, since the container runtime keeps its whole
     * image store under it.
     *
     * @return Path of the vault file.
     */
    public Path vaultFile() {
        final String override = System.getenv(VAULT_VARIABLE);
        return override == null || override.isBlank()
                ? xdg.data().resolve("vault.bin") : Path.of(override);
    }

    /**
     * Returns the keyring description under which this vault's passphrase is cached.
     * <p>
     * Keyed by the vault it unlocks. A single shared name meant unlocking one vault silently
     * replaced the cached passphrase of another, which is how an acceptance run could log the
     * operator out of their own.
     *
     * @return Keyring description.
     */
    public String vaultKeyringKey() {
        final Path file = vaultFile();
        return "sokar:vault:" + Integer.toHexString(file.toAbsolutePath().toString().hashCode());
    }

    /**
     * Returns the directory podman reads hook descriptors from.
     *
     * @return Hook directory.
     */
    public Path hooksDirectory() {
        // Not under the Sokar config directory: podman decides where to look, and it looks here.
        return xdg.config().getParent().resolve("containers/oci/hooks.d");
    }

    /**
     * Returns the directory recording where each project's own file is, one file per project.
     * <p>
     * Durable rather than runtime state: an interface asking what projects exist has to be
     * answered on a machine where nothing is running. A directory rather than a document because
     * two tasks can start at the same moment, and a file each is a race that cannot happen.
     *
     * @return The registry directory.
     */
    public Path projectRegistry() {
        return xdg.data().resolve("projects");
    }

    /**
     * Returns where a readable copy of waiting work is put.
     * <p>
     * <strong>Visible, in the home directory, and not under the data directory.</strong> This is
     * the one thing Sokar makes for a person to open in their own editor rather than for itself
     * to read back, and {@code ~/.local/share} is where neither a file dialog nor a person looks.
     * The command exists so the safe way is also the convenient one; hiding its result would have
     * repaired the convenience in one place by breaking it in another.
     * <p>
     * It used to default beside the project file, which normally sits inside the operator's git
     * checkout - so reviewing an agent's work left an untracked directory in a repository Sokar
     * promises not to touch, committable by accident and cleaned up by nothing. Reported from a
     * test machine where one had sat for two days.
     *
     * @param project Project the work belongs to.
     * @param name The waiting ref's name.
     * @return The directory to check out into.
     */
    public Path reviewCheckout(String project, String name) {
        return xdg.home().resolve("sokar").resolve("reviews").resolve(project + "-" + name);
    }

    /**
     * Returns the file holding this node's identity.
     * <p>
     * Under the data directory, so it is per OS user without anything having to say so: two
     * accounts on one machine are two nodes, and this is what makes that answerable rather than
     * merely true.
     *
     * @return The identity file, which may not exist yet.
     */
    public Path nodeIdFile() {
        return xdg.data().resolve("node-id");
    }

    /**
     * Returns the directory recording each project's last measured distance from its upstream.
     * <p>
     * Beside the registry and shaped the same way, for the same reason: written from a timer while
     * every listing reads it, so one file per project is a race that cannot happen.
     *
     * @return The directory.
     */
    /**
     * Returns the directory recording what backups have been taken.
     * <p>
     * A bundle goes wherever the operator names it, so this is the only thing that knows one was
     * ever taken. It records where, not what: the file it names can be moved or replaced
     * afterwards and nothing here would know.
     *
     * @return The directory, which may not exist yet.
     */
    public Path backupRecords() {
        return xdg.data().resolve("backups");
    }

    public Path upstreamRecords() {
        return xdg.data().resolve("upstream");
    }

    /**
     * Returns the socket the daemon serves on.
     * <p>
     * Named here rather than in the daemon, because the CLI has to find the same file: it is what
     * {@code sokar daemon connect} bridges to, and a second spelling of this path is a client that
     * connects to nothing on a machine where the daemon is running perfectly.
     *
     * @return The daemon's socket.
     */
    public Path daemonSocket() {
        return xdg.runtime().resolve("sokard.sock");
    }

    /**
     * Returns the directory holding the runtime state of one container.
     *
     * @param container Container name.
     * @return State directory.
     */
    public Path containerState(String container) {
        // Under the runtime directory, so the kernel removes it when the session ends and no
        // stale sidecar can be picked up by a later run.
        return xdg.runtime().resolve(container);
    }

    /**
     * Returns the file holding the key this machine signs messages with.
     * <p>
     * Under the state directory, mode {@code 0600}, and never mounted anywhere. A key a task
     * container could reach is a key it could copy, which is why the task never signs and the host
     * always does - and why what a signature proves is that this installation vouched for the
     * message, not that a particular agent typed it.
     *
     * @return The key file, which may not exist yet.
     */
    public Path messageKey() {
        return xdg.state().resolve("message-key");
    }

    /**
     * Returns the filter that decides what a message may contain, or {@code null} when this
     * machine has none.
     * <p>
     * A machine without it sends nothing: the filter is what stands between an agent's outbox and
     * every transport, so its absence is a closed door rather than an open one.
     *
     * @return The executable, or {@code null}.
     */
    public @org.jspecify.annotations.Nullable Path messageFilter() {
        for (final Path candidate : java.util.List.of(
                xdg.data().resolve("filter").resolve(MESSAGE_FILTER),
                Path.of("/usr/libexec/sokar").resolve(MESSAGE_FILTER))) {
            if (java.nio.file.Files.isRegularFile(candidate)
                    && java.nio.file.Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the transports installed for this user, their own before the packaged ones.
     *
     * @return The directory.
     */
    public TransportDirectory transportDirectory() {
        return TransportDirectory.standard(xdg.data());
    }

    /**
     * Returns the root of one task's mailbox.
     * <p>
     * Under the state directory rather than the runtime one, because a conversation has to survive
     * {@code stop}, {@code start} and a machine restart, and the kernel clears the runtime
     * directory at boot. It is the task's: made when the task is made, deleted when the task is
     * removed, and untouched in between.
     *
     * @param container Container name.
     * @return Mailbox root, which is the host's half - only what is under it is mounted.
     */
    public Path mailbox(String container) {
        return xdg.state().resolve("mail").resolve(container);
    }

    /**
     * Returns the file holding one task's clearance decisions.
     * <p>
     * Under the state directory, not beside the rest of the task's files. Everything else a task
     * writes is in the runtime directory, which the kernel clears at logout and
     * {@code task stop --remove} deletes - and a record of what an agent tried to reach that goes
     * when the task goes is not an audit record. Named by the container, so it is per run: a
     * decision made for one run of a task is not silently in force for the next.
     *
     * @param container Container name.
     * @return The journal file.
     */
    public Path clearanceJournal(String container) {
        return xdg.state().resolve("clearance").resolve(container + ".jsonl");
    }

    /**
     * Returns the file unhandled failures are appended to.
     * <p>
     * Under the state directory, which is where logs and audit trails live and which
     * {@code doctor} already prints - so somebody told a command failed has one place to look and
     * was already shown it.
     *
     * @return The failure log.
     */
    public Path failureLog() {
        return xdg.state().resolve("failures.log");
    }

    /**
     * Returns the file podman is pointed at when Sokar starts a container.
     * <p>
     * Under the runtime directory rather than beside podman's own configuration: it applies to
     * Sokar's containers alone, so it has no business outliving the session or being read by a
     * container the operator starts.
     *
     * @return Network configuration file.
     */
    public Path networkConfiguration() {
        return xdg.runtime().resolve("containers.conf");
    }

    /**
     * Returns the build context directory for a project's image.
     *
     * @param project Project name.
     * @return Build context directory.
     */
    public Path buildContext(String project) {
        return xdg.data().resolve("build").resolve(project);
    }
}
