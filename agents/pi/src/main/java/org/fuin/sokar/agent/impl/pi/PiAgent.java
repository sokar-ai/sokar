package org.fuin.sokar.agent.impl.pi;

import org.fuin.sokar.agent.api.ContainerSetup;
import org.fuin.sokar.agent.api.YamlAgent;

/**
 * Pi.
 * <p>
 * One override: the endpoint and the token go into an extension Pi discovers, because it has no
 * variable for either. Everything else is {@code pi.yaml}.
 */
public final class PiAgent extends YamlAgent {

    /**
     * Constructor.
     */
    public PiAgent() {
        super("pi");
    }

    @Override
    public ContainerSetup containerSetup() {
        return new PiContainerSetup();
    }

    /**
     * Entry point of the {@code sokar-agent-pi} binary.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        org.fuin.sokar.agent.api.AgentMain.run(new PiAgent(), args);
    }
}
