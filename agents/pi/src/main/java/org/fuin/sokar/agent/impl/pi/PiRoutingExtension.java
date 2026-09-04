package org.fuin.sokar.agent.impl.pi;

import org.fuin.sokar.wire.Json;

/**
 * The extension that points Pi at Sokar's broker.
 * <p>
 * Pi cannot be redirected with an environment variable: only Azure has one, and every other
 * provider's endpoint is fixed unless an extension overrides it. Extensions are auto-discovered
 * from {@code ~/.pi/agent/extensions}, so this is a file to place rather than a command to run.
 * <p>
 * <strong>This belongs to the provider, not to Pi.</strong> The path under the base URL is
 * OpenRouter's - it serves the OpenAI dialect under {@code /api/v1} - and a second agent reaching
 * the same provider would need the same suffix.
 */
final class PiRoutingExtension {

    /** Auto-discovered by Pi; the name only has to be unique and end in {@code .ts}. */
    static final String FILE = "/home/agent/.pi/agent/extensions/sokar-route.ts";

    /** Provider Pi already knows, whose endpoint is overridden rather than added. */
    static final String PROVIDER = "openrouter";

    /** Where OpenRouter serves the dialect Pi speaks. A base without it answers 404. */
    static final String DIALECT_PATH = "/api/v1";

    private PiRoutingExtension() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the extension that sends Pi's requests to the broker instead of the provider.
     *
     * @param endpoint Base URL Sokar is listening on, inside this container's namespace.
     * @param token Task-scoped token to present, standing in for the real credential.
     * @return File content.
     */
    static String document(String endpoint, String token) {
        // Written as JSON literals rather than pasted into the source: a token is opaque and a
        // stray quote in it would otherwise produce an extension that does not parse.
        return """
                // Written by Sokar for one task. The key here is a task-scoped token, not a
                // credential: it is only accepted by the broker this baseUrl points at.
                export default function (pi) {
                    pi.registerProvider(%s, { baseUrl: %s, apiKey: %s });
                }
                """.formatted(Json.write(PROVIDER),
                        Json.write(trimSlash(endpoint) + DIALECT_PATH), Json.write(token));
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
