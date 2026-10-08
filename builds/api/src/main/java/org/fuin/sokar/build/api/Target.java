package org.fuin.sokar.build.api;

/**
 * Where a reader asks, and with what: everything a call needs, since a reader keeps nothing between calls.
 * <p>
 * The token is here because a reader holds no secret of its own: Sokar reads it from the vault on the host and hands
 * it over the reader's owner-only socket in each call, never in an argument, an environment or a file.
 *
 * @param upstream The repository as the project file names it, such as {@code git@github.com:owner/name.git}; the
 *         reader takes it apart, since only it knows how its forge spells a repository.
 * @param api The forge API's address; "" for the forge's own public one.
 * @param token The forge token.
 */
public record Target(String upstream, String api, String token) {

    /**
     * Constructor with checks.
     *
     * @param upstream The repository.
     * @param api The API, or "".
     * @param token The token.
     */
    public Target {
        if (upstream.isBlank()) {
            throw new IllegalArgumentException("A build is read for a repository; none was named");
        }
        if (token.isBlank()) {
            throw new IllegalArgumentException("A build is read with a token; none was given");
        }
    }

    /** Never the token: a target ends up in a message, and a message in a log. */
    @Override
    public String toString() {
        return "Target[upstream=" + upstream + ", api=" + (api.isEmpty() ? "the forge's own" : api) + "]";
    }
}
