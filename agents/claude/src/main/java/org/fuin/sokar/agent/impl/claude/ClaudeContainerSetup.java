package org.fuin.sokar.agent.impl.claude;

import java.util.List;
import org.fuin.sokar.agent.api.ContainerFile;
import org.fuin.sokar.agent.api.ContainerSetup;

/**
 * What Claude Code needs in a fresh container before it will run.
 * <p>
 * The endpoint is not used: this agent is told where to send its requests with environment
 * variables, which is the shape it declared. An agent that can only be given a URL writes it into
 * a file here instead.
 * <p>
 * Two files for two different owners, which is why each is produced somewhere else:
 * {@link ClaudeFirstRun} is the agent's own state, and {@link AnthropicCredentialFile} is the
 * shape the provider expects a credential in. This class only says that a container needs both.
 */
public class ClaudeContainerSetup implements ContainerSetup {

    @Override
    public List<ContainerFile> files(org.fuin.sokar.agent.api.SetupContext context) {
        return List.of(
                ContainerFile.of(ClaudeFirstRun.FILE,
                        ClaudeFirstRun.document(context.workspace())),
                ContainerFile.secret(AnthropicCredentialFile.FILE,
                        AnthropicCredentialFile.document(context.token(),
                                context.credentialType())));
    }
}
