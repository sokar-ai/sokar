package org.fuin.sokar.agent.impl.claude;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.wire.Json;

/**
 * The shape the provider expects a stored credential to have.
 * <p>
 * <strong>This belongs to the provider, not to the agent.</strong> The field names, and the fact
 * that an API key and a subscription token live under different ones, are Anthropic's; a second
 * agent reaching the same provider would need the same file. It is kept apart from
 * {@link ClaudeFirstRun} for that reason, so the boundary is visible before anything is built on
 * it.
 */
final class AnthropicCredentialFile {

    /** Where a login would have written it. */
    static final String FILE = "/home/agent/.claude/.credentials.json";

    /** Far enough ahead that a task never sees the token as expired. */
    static final long EXPIRES_AT = 4102444800000L;

    private AnthropicCredentialFile() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the file a login would have written, holding the task's token.
     *
     * @param token Token to present, standing in for the real credential.
     * @param credentialType Kind it stands in for.
     * @return File content.
     */
    static String document(String token, String credentialType) {
        if (ClaudeCredentialExtractor.API_KEY.equals(credentialType)) {
            return Json.write(Map.of("apiKey", token));
        }
        final Map<String, Object> oauth = new LinkedHashMap<>();
        oauth.put("accessToken", token);
        oauth.put("expiresAt", EXPIRES_AT);
        oauth.put("subscriptionType", "max");
        return Json.write(Map.of("claudeAiOauth", oauth));
    }
}
