package org.fuin.sokar.agent.impl.claude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.agent.api.ContainerFile;
import org.fuin.sokar.agent.api.ContainerSetup;
import org.fuin.sokar.wire.Json;

/**
 * What Claude Code needs in a fresh container before it will run.
 * <p>
 * Measured, not documented. A container has never been logged in, so the CLI runs its first-run
 * wizard: it asks for a theme, then offers a login menu, then asks whether the workspace is
 * trusted. The login menu belongs to that wizard rather than to any check of the credential -
 * with the wizard marked done, the stored token is used without question, phantom or not.
 */
public class ClaudeContainerSetup implements ContainerSetup {

    /** Where the CLI keeps the answers to its first-run questions. */
    static final String CONFIG = "/home/agent/.claude.json";

    /** Where the CLI keeps what its own login would have written. */
    static final String CREDENTIALS = "/home/agent/.claude/.credentials.json";

    /** Far enough ahead that a task never sees the token as expired. */
    static final long EXPIRES_AT = 4102444800000L;

    @Override
    public List<ContainerFile> files(String token, String credentialType, String workspace) {
        return List.of(
                ContainerFile.of(CONFIG, Json.write(config(workspace))),
                ContainerFile.secret(CREDENTIALS, Json.write(credentials(token, credentialType))));
    }

    /**
     * Returns the answers to the questions a fresh container would be asked.
     *
     * @param workspace Directory the agent works in.
     * @return Configuration document.
     */
    private static Map<String, Object> config(String workspace) {
        final Map<String, Object> project = new LinkedHashMap<>();
        // Asked once per directory. In a container that directory is new every time, so without
        // this every task begins by asking whether the operator trusts their own project.
        project.put("hasTrustDialogAccepted", true);
        project.put("allowedTools", List.of());

        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("hasCompletedOnboarding", true);
        root.put("theme", "dark");
        root.put("projects", Map.of(workspace, project));
        return root;
    }

    /**
     * Returns the credential file the CLI's own login would have written.
     *
     * @param token Token to present, standing in for the real credential.
     * @param credentialType Kind it stands in for.
     * @return Credentials document.
     */
    private static Map<String, Object> credentials(String token, String credentialType) {
        if (ClaudeCredentialExtractor.API_KEY.equals(credentialType)) {
            return Map.of("apiKey", token);
        }
        final Map<String, Object> oauth = new LinkedHashMap<>();
        oauth.put("accessToken", token);
        oauth.put("expiresAt", EXPIRES_AT);
        oauth.put("subscriptionType", "max");
        return Map.of("claudeAiOauth", oauth);
    }
}
