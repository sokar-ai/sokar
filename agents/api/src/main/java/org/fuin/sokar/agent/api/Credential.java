package org.fuin.sokar.agent.api;

import java.util.Map;

/**
 * A credential taken from an agent's own config, on its way into the vault.
 *
 * @param type What kind it is, for example {@code oauth} or {@code api-key}. Decides which
 *        environment variable it is later injected as.
 * @param secret The value itself.
 * @param attributes Anything else worth keeping, such as a refresh token or an expiry.
 */
public record Credential(String type, String secret, Map<String, String> attributes) {

    /**
     * The attribute holding what renews this credential: kept hidden on the host, never in a task. Renewed only
     * together with {@link #TOKEN_URL} and {@link #CLIENT_ID}.
     */
    public static final String REFRESH_TOKEN = "refreshToken";

    /** The attribute holding when the credential ends, in epoch milliseconds. */
    public static final String EXPIRES_AT = "expiresAt";

    /** The attribute naming where the credential is renewed: the service's OAuth token endpoint. */
    public static final String TOKEN_URL = "tokenUrl";

    /** The attribute naming the client the credential was granted to, which a renewal has to name too. */
    public static final String CLIENT_ID = "clientId";

    /**
     * Constructor with all data.
     *
     * @param type Credential type.
     * @param secret The value.
     * @param attributes Extra fields.
     */
    public Credential {
        if (type.isBlank()) {
            throw new AgentException("A credential needs a type");
        }
        if (secret.isBlank()) {
            throw new AgentException("A credential of type '" + type + "' has no value");
        }
        attributes = Map.copyOf(attributes);
    }

    /**
     * Creates a credential with no extra attributes.
     *
     * @param type Credential type.
     * @param secret The value.
     * @return New credential.
     */
    public static Credential of(String type, String secret) {
        return new Credential(type, secret, Map.of());
    }

    @Override
    public String toString() {
        // Credentials reach logs and exception messages by accident.
        return "Credential[" + type + ", " + secret.length() + " chars]";
    }
}
