package org.fuin.sokar.agent.impl.claude;

import org.fuin.sokar.agent.api.CredentialExtractor;
import org.fuin.sokar.agent.api.LogFormatter;
import org.fuin.sokar.agent.api.YamlAgent;

/**
 * Claude Code.
 * <p>
 * Two overrides, both because the definition genuinely cannot express them, and both living here
 * rather than in Sokar:
 * <ul>
 * <li>the credential is an OAuth token nested in a JSON file Claude writes at login;</li>
 * <li>the output is a stream of JSON objects rather than lines of text.</li>
 * </ul>
 * Everything else is {@code claude.yaml}.
 */
public final class ClaudeAgent extends YamlAgent {

    /**
     * Constructor.
     */
    public ClaudeAgent() {
        super("claude");
    }

    @Override
    public CredentialExtractor credentialExtractor() {
        return new ClaudeCredentialExtractor();
    }

    @Override
    public org.fuin.sokar.agent.api.ContainerSetup containerSetup() {
        return new ClaudeContainerSetup();
    }

    @Override
    public LogFormatter logFormatter() {
        return new ClaudeStreamJsonFormatter();
    }

    /**
     * Entry point of the {@code sokar-agent-claude} binary.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        org.fuin.sokar.agent.api.AgentMain.run(new ClaudeAgent(), args);
    }
}
