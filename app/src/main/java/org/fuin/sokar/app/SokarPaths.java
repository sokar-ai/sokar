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

    /**
     * Returns the paths for the current user, with the hook binaries in {@code ~/.local/bin}.
     *
     * @return Paths.
     */
    public static SokarPaths current() {
        return new SokarPaths(XdgPaths.current(),
                Path.of(System.getProperty("user.home"), ".local", "bin"));
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
