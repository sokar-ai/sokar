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
 * <p>
 * The endpoint is passed in because some agents cannot be pointed at a broker with a variable at
 * all: their endpoint lives in a configuration file, and only the agent knows its shape.
 */
@FunctionalInterface
public interface ContainerSetup {

    /**
     * Returns the files to place before the agent starts.
     *
     * @param context What the agent is told about this task.
     * @return Files to write, possibly empty.
     */
    List<ContainerFile> files(SetupContext context);

    /**
     * Setup for an agent that needs nothing placed.
     *
     * @return Setup returning no files.
     */
    static ContainerSetup none() {
        return context -> List.of();
    }
}
