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
        this(xdg, binaryDirectory, packaged(PACKAGED_HOOKS), packaged(AgentDirectory.PACKAGED));
    }

    /**
     * Names a directory to read the packaged locations under instead of {@code /}. Set by the build for its unit tests
     * and nowhere else: a test that read {@code /usr/libexec/sokar} passed on a machine without the package and failed
     * on one with it - eight of them, the morning the package was installed on a developer's machine.
     */
    static final String PACKAGED_ROOT = "sokar.packaged.root";

    /**
     * Returns where a packaged location is on this machine.
     *
     * @param absolute The location as the package installs it.
     * @return It, under {@link #PACKAGED_ROOT} when that is set.
     */
    static Path packaged(Path absolute) {
        final String root = System.getProperty(PACKAGED_ROOT);
        return root == null || root.isBlank() ? absolute
                : Path.of(root).resolve(absolute.getRoot() == null ? absolute : absolute.getRoot().relativize(absolute));
    }

    /** Where the sokar package puts the hook binaries. */
    static final Path PACKAGED_HOOKS = Path.of("/usr/libexec/sokar/hooks");

    /** Where the sokar package puts the SELinux policy source and its installer. */
    static final Path PACKAGED_SELINUX = Path.of("/usr/share/sokar/selinux");

    /** Name of a hook binary, used to tell an install apart from an empty directory. */
    private static final String MARKER = "sokar-hook-nft";

    /** Names a spool other than the machine's own, for a test or an unusual machine. */
    public static final String SPOOL_VARIABLE = "SOKAR_SPOOL";

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
                org.fuin.sokar.core.config.XdgPaths.home(System::getenv)
                        .resolve(".local").resolve("bin"), PACKAGED_HOOKS));
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
     * Returns where the users of this machine exchange messages, drops and keys.
     * <p>
     * Overridable through {@link #SPOOL_VARIABLE}, for the same reason the vault's path is: a test
     * needs somewhere that is not the machine's real one, and a machine may keep it elsewhere.
     * The default is what the setup script makes.
     *
     * @return The directory, which need not exist.
     */
    public Path spool() {
        final String override = System.getenv(SPOOL_VARIABLE);
        return override == null || override.isBlank()
                ? Path.of("/var/spool/sokar") : Path.of(override);
    }

    /**
     * Returns the directory podman reads hook descriptors from.
     *
     * @return Hook directory.
     */
    public Path hooksDirectory() {
        // Not under the Sokar config directory: podman decides where to look, and it looks here.
        return java.util.Objects.requireNonNull(xdg.config().getParent(), "the config directory has a parent")
                .resolve("containers/oci/hooks.d");
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
     * Returns where the files of a task's own files: its record, its state, its session, its clearance journal and its image's build context are.
     *
     * @return Their locations.
     */
    public TaskPaths tasks() {
        return new TaskPaths(this);
    }

    /**
     * Returns where the files of messages: the mailboxes, the keys, the moderation and the transports are.
     *
     * @return Their locations.
     */
    public MessagingPaths messaging() {
        return new MessagingPaths(this);
    }

    /**
     * Returns where the files of projects: what is followed, its clones, its signers and its records are.
     *
     * @return Their locations.
     */
    public ProjectPaths projects() {
        return new ProjectPaths(this);
    }

    /**
     * Returns where the files of the vault and the credentials declared on this machine are.
     *
     * @return Their locations.
     */
    public VaultPaths vault() {
        return new VaultPaths(this);
    }

    /**
     * Returns where the files of egress: its sets and the network a task is on are.
     *
     * @return Their locations.
     */
    public EgressPaths egress() {
        return new EgressPaths(this);
    }

    /**
     * Returns where the files of agents and providers are.
     *
     * @return Their locations.
     */
    public AgentPaths agents() {
        return new AgentPaths(this);
    }

    /**
     * Returns where the files of the gate: its review checkouts and its backups are.
     *
     * @return Their locations.
     */
    public GatePaths gate() {
        return new GatePaths(this);
    }
}
