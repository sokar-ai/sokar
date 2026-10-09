package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.jspecify.annotations.Nullable;

/**
 * Where Sokar keeps the files of agents and providers.
 * <p>
 * One class per area, beside {@link SokarPaths}, which keeps only the roots and what belongs to the machine as a
 * whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record AgentPaths(SokarPaths paths) {

    /**
     * Returns the agent directories to scan, the operator's own first.
     *
     * @return Directory scanner.
     */
    public AgentDirectory agentDirectory() {
        return new AgentDirectory(java.util.List.of(paths.xdg().data().resolve("agents"), paths.packagedAgents()),
                java.util.List.of(paths.xdg().data().resolve("agents.d"),
                        SokarPaths.packaged(AgentDirectory.PACKAGED_DESCRIPTIONS)));
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
                paths.xdg().data().resolve("providers"),
                SokarPaths.packaged(org.fuin.sokar.agent.api.ProviderDirectory.PACKAGED)));
    }
}
