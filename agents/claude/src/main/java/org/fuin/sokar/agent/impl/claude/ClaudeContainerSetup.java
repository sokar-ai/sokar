package org.fuin.sokar.agent.impl.claude;

import java.util.List;
import org.fuin.sokar.agent.api.ContainerFile;
import org.fuin.sokar.agent.api.ContainerSetup;

/**
 * What Claude Code needs in a fresh container before it will run.
 * <p>
 * Two files for two different owners, which is why each is produced somewhere else:
 * {@link ClaudeFirstRun} is the agent's own state, and {@link AnthropicCredentialFile} is the
 * shape the provider expects a credential in. This class only says that a container needs both.
 */
public class ClaudeContainerSetup implements ContainerSetup {

    @Override
    public List<ContainerFile> files(String token, String credentialType, String workspace) {
        return List.of(
                ContainerFile.of(ClaudeFirstRun.FILE, ClaudeFirstRun.document(workspace)),
                ContainerFile.secret(AnthropicCredentialFile.FILE,
                        AnthropicCredentialFile.document(token, credentialType)));
    }
}
