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

    /** Name of a hook binary, used to tell an install apart from an empty directory. */
    private static final String MARKER = "sokar-hook-nft";

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
     * Returns the build context directory for a project's image.
     *
     * @param project Project name.
     * @return Build context directory.
     */
    public Path buildContext(String project) {
        return xdg.data().resolve("build").resolve(project);
    }
}
