package org.fuin.sokar.agent.api;

import java.util.List;

/**
 * The state an agent needs in a task container before it will run unattended.
 * <p>
 * A vendor's tool typically expects to have been used interactively at least once: a first-run
 * wizard answered, a folder trusted, a credential stored where its own login would have put it. A
 * fresh container has none of that, so the tool stops and asks. Sokar cannot answer those
 * questions - they are the vendor's, and they change - so the agent answers them and Sokar writes
 * the bytes.
 */
@FunctionalInterface
public interface ContainerSetup {

    /**
     * Returns the files to place before the agent starts.
     *
     * @param token Token the agent should present, standing in for the real credential.
     * @param credentialType Kind of credential it stands in for.
     * @param workspace Absolute path of the working directory inside the container.
     * @return Files to write, possibly empty.
     */
    List<ContainerFile> files(String token, String credentialType, String workspace);

    /**
     * Setup for an agent that needs nothing placed.
     *
     * @return Setup returning no files.
     */
    static ContainerSetup none() {
        return (token, credentialType, workspace) -> List.of();
    }
}
