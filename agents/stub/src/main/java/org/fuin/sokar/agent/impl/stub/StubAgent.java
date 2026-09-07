package org.fuin.sokar.agent.impl.stub;

import org.fuin.sokar.agent.api.AgentMain;
import org.fuin.sokar.agent.api.YamlAgent;

/**
 * The agent the acceptance suite drives.
 * <p>
 * It exists so the suite keeps working once the real agents live in their own repositories.
 * Nothing around it is faked: a real image is built, a real container runs, and the firewall and
 * credential proxy are the ones Sokar ships. Only the tool inside is ours - a short script the
 * definition writes into the image, which asks for one name it is granted and one it is refused,
 * and redeems its task token through the proxy.
 * <p>
 * That is a stronger test than a vendor CLI for the parts the core owns, because those reaches
 * are deliberate rather than a side effect of whatever the vendor shipped that month. What it
 * cannot tell you is whether a real CLI honors a socket or stays inside its declared domains -
 * that belongs to the agent's own repository.
 * <p>
 * No overrides. Everything is {@code stub.yaml}, which is the point: if this agent needed
 * behavior Sokar could not express as data, the suite would be testing the exception rather
 * than the rule.
 */
public final class StubAgent extends YamlAgent {

    /** Where {@code stub.yaml} writes the tool; {@code binary} in the definition names it. */
    static final String TARGET = "/usr/local/bin/sokar-stub-cli";

    /**
     * Constructor.
     */
    public StubAgent() {
        super("stub");
    }

    /**
     * Entry point of the {@code sokar-agent-stub} binary.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        AgentMain.run(new StubAgent(), args);
    }
}
