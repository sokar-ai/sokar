package org.fuin.sokar.agent.api;

import java.util.List;

/**
 * The container-build fragments one agent contributes.
 * <p>
 * These are shell, and there is no making them otherwise: installing a third-party CLI is
 * irreducibly shell, and about a third of the reference implementation's Claude definition is
 * exactly this. Keeping them inside the agent's own module at least confines the churn to one
 * directory.
 *
 * @param asRoot Fragments run as root.
 * @param asAgent Fragments run as the unprivileged agent user.
 */
public record ImageLayer(List<String> asRoot, List<String> asAgent) {

    /**
     * Constructor with all data.
     *
     * @param asRoot Fragments run as root.
     * @param asAgent Fragments run as the agent user.
     */
    public ImageLayer {
        asRoot = List.copyOf(asRoot);
        asAgent = List.copyOf(asAgent);
    }

    /**
     * Tells whether this layer adds anything.
     *
     * @return {@code true} if there is nothing to run.
     */
    public boolean isEmpty() {
        return asRoot.isEmpty() && asAgent.isEmpty();
    }
}
