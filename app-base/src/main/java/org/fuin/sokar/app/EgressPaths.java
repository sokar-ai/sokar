package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.jspecify.annotations.Nullable;

/**
 * Where Sokar keeps the files of egress: its sets and the network a task is on.
 * <p>
 * One class per area, beside {@link SokarPaths}, which keeps only the roots and what belongs to the machine as a
 * whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record EgressPaths(SokarPaths paths) {

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
                paths.xdg().data().resolve("egress"),
                SokarPaths.packaged(org.fuin.sokar.shield.EgressSetDirectory.PACKAGED)));
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
        return paths.xdg().runtime().resolve("containers.conf");
    }
}
