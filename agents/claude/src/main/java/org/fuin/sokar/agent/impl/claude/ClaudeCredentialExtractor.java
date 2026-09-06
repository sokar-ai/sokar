package org.fuin.sokar.agent.impl.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.fuin.sokar.agent.api.AgentException;
import org.fuin.sokar.agent.api.Credential;
import org.fuin.sokar.agent.api.CredentialExtractor;
import org.fuin.sokar.wire.Json;

/**
 * Reads the OAuth token Claude writes at login.
 * <p>
 * Neither of the agent API's two shapes fits: the token sits under a nested key, and the
 * refresh token
 * and expiry beside it are worth keeping. So this is an override - and the point of the structure
 * is that the override lives here, in Claude's own module, rather than as an entry in a
 * name-keyed registry that every other agent has to be looked up in.
 * <p>
 * An API key, when the operator used one instead, is a plain field at the top level. Both are
 * handled, because the vault needs to know which it got: they go into different environment
 * variables, and sending one as the other fails in a way that looks like a bad key.
 */
public class ClaudeCredentialExtractor implements CredentialExtractor {

    /** File Claude writes its credentials into. */
    static final String CREDENTIALS_FILE = ".credentials.json";

    /** Credential type for an OAuth token. */
    static final String OAUTH = "oauth";

    /** Credential type for an API key. */
    static final String API_KEY = "api-key";

    @Override
    public Optional<Credential> extract(Path configDirectory) {

        final Path file = configDirectory.resolve(CREDENTIALS_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        final Object parsed;
        try {
            parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new AgentException("Cannot read " + file, ex);
        } catch (RuntimeException ex) {
            throw new AgentException("Cannot parse " + file + ": " + ex.getMessage(), ex);
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new AgentException(file + " is not a JSON object");
        }

        if (root.get("claudeAiOauth") instanceof Map<?, ?> oauth) {
            return oauth(file, oauth);
        }
        if (root.get("apiKey") instanceof String apiKey && !apiKey.isBlank()) {
            return Optional.of(Credential.of(API_KEY, apiKey));
        }
        // The file exists but holds neither. Saying so beats reporting "not logged in", which
        // would send the operator to run a login that has already succeeded.
        throw new AgentException(file + " holds neither an OAuth token nor an API key");
    }

    private Optional<Credential> oauth(Path file, Map<?, ?> oauth) {

        if (!(oauth.get("accessToken") instanceof String token) || token.isBlank()) {
            throw new AgentException(file + " has an OAuth block with no access token");
        }

        final Map<String, String> attributes = new LinkedHashMap<>();
        if (oauth.get("refreshToken") instanceof String refresh && !refresh.isBlank()) {
            attributes.put("refreshToken", refresh);
        }
        if (oauth.get("expiresAt") instanceof Number expiry) {
            // Kept so the vault can tell a stale token from a wrong one later, which are very
            // different problems wearing the same error message.
            attributes.put("expiresAt", String.valueOf(expiry.longValue()));
        }
        return Optional.of(new Credential(OAUTH, token, attributes));
    }
}
