package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;

/**
 * Where Sokar puts the files that belong to one task run.
 *
 * @param xdg The base directories.
 * @param binaryDirectory Directory the hook binaries are installed in.
 */
public record SokarPaths(XdgPaths xdg, Path binaryDirectory) {

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
