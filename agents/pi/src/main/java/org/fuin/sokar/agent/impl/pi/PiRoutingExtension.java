package org.fuin.sokar.agent.impl.pi;

import org.fuin.sokar.wire.Json;

/**
 * The extension that points Pi at Sokar's broker.
 * <p>
 * Pi cannot be redirected with an environment variable: only Azure has one, and every other
 * provider's endpoint is fixed unless an extension overrides it. Extensions are auto-discovered
 * from {@code ~/.pi/agent/extensions}, so this is a file to place rather than a command to run.
 * <p>
 * <strong>Neither the provider's name nor its path is written here any more.</strong> Both were
 * constants, and changing them meant rebuilding this binary - which is exactly what pointing Pi
 * at a second provider once cost. The name arrives with the task and the path is already on the
 * endpoint Sokar hands over.
 */
final class PiRoutingExtension {

    /** Auto-discovered by Pi; the name only has to be unique and end in {@code .ts}. */
    static final String FILE = "/home/agent/.pi/agent/extensions/sokar-route.ts";

    private PiRoutingExtension() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the extension that sends Pi's requests to the broker instead of the provider.
     *
     * @param provider Name of the provider, as Pi already knows it.
     * @param endpoint Base URL Sokar is listening on, inside this container's namespace, with
     *        the dialect's path already on it.
     * @param token Task-scoped token to present, standing in for the real credential.
     * @return File content.
     */
    static String document(String provider, String endpoint, String token) {
        // Written as JSON literals rather than pasted into the source: a token is opaque and a
        // stray quote in it would otherwise produce an extension that does not parse.
        return """
                // Written by Sokar for one task. The key here is a task-scoped token, not a
                // credential: it is only accepted by the broker this baseUrl points at.
                export default function (pi) {
                    pi.registerProvider(%s, { baseUrl: %s, apiKey: %s });
                }
                """.formatted(Json.write(provider), Json.write(endpoint), Json.write(token));
    }
}
