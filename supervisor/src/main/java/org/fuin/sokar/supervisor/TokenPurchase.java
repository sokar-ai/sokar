package org.fuin.sokar.supervisor;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Buys a short-lived access token with a credential the broker holds: OAuth 2.0 {@code client_credentials}.
 * <p>
 * <strong>The container never performs the exchange.</strong> The vault holds the client secret, the token
 * URL and the scopes; this buys the access token on the host, keeps it in memory until shortly before it
 * expires, and the broker attaches it. The container presents its phantom token and sees neither the secret
 * nor the token bought with it. Decided by the operator on 2026-09-29: per task, in memory, never written to
 * disk - so no live token rests outside the vault, and tasks do not depend on each other's cache.
 * <p>
 * <strong>One exchange, however many requests are waiting.</strong> Twenty requests arriving with no valid
 * token wait for the one that buys it; they do not each buy one.
 */
public final class TokenPurchase {

    /** The kind a vault entry holding a client secret carries. */
    public static final String KIND = "client-credentials";

    /** How long before its expiry a token is bought again, so no request is sent with one about to lapse. */
    static final Duration MARGIN = Duration.ofSeconds(60);

    /** Assumed when the authorization server says nothing about how long its token lasts. */
    static final Duration UNSTATED = Duration.ofMinutes(5);

    /**
     * What the authorization server did not give, said as itself.
     * <p>
     * Kept apart from a wrong key and from an expired task token: the operator's fix is at the
     * authorization server, and a message that sends them to the vault or to the task costs an afternoon.
     */
    public static class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }

        Refused(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A grant the service no longer honours - revoked or expired: a person has to authorize again. Kept apart
     * so whoever finds it can ask that person, rather than report a failure.
     */
    public static final class Ended extends Refused {

        private static final long serialVersionUID = 1L;

        Ended(String message) {
            super(message);
        }
    }

    /**
     * What is needed to buy a token.
     *
     * @param tokenUrl Where to buy it, https.
     * @param clientId The client's id.
     * @param clientSecret The client's secret.
     * @param scopes The scopes, space-separated, or empty.
     * @param audience The audience, or {@code null}.
     */
    public record Client(String tokenUrl, String clientId, String clientSecret, String scopes,
            @Nullable String audience) {

        /**
         * Reads a client from a vault entry's secret and settings.
         *
         * @param secret The entry's value, the client secret.
         * @param settings The entry's settings.
         * @return The client.
         * @throws IllegalArgumentException If a setting it needs is missing or the token URL is not https.
         */
        public static Client of(String secret, Map<String, String> settings) {
            final String url = settings.get("token_url");
            final String id = settings.get("client_id");
            if (url == null || id == null) {
                throw new IllegalArgumentException("a client-credentials entry needs the settings token_url and"
                        + " client_id; store it with 'sokar vault put <name> --type " + KIND
                        + " --setting token_url=... --setting client_id=...'");
            }
            if (!Grants.secure(url)) {
                throw new IllegalArgumentException("the token URL must be https, or http on loopback, not '"
                        + url + "'");
            }
            return new Client(url, id, secret, settings.getOrDefault("scopes", ""), settings.get("audience"));
        }
    }

    /**
     * A grant a person gave once: spent with its refresh token, which the service may replace on every use.
     *
     * @param tokenUrl Where to buy, https.
     * @param clientId The client's id.
     * @param clientSecret The client's secret, or {@code null} for a public client.
     * @param refreshToken The refresh token in force now.
     * @param rotated Told a replacement when the service issues one, so the vault keeps the one in force.
     */
    public record Refresh(String tokenUrl, String clientId, @Nullable String clientSecret, String refreshToken,
            java.util.function.Consumer<String> rotated) {
    }

    private final Client client;

    private final @Nullable Refresh refresh;

    private @Nullable String refreshToken;

    private final HttpClient http;

    private final Clock clock;

    private @Nullable String token;

    private Instant good = Instant.MIN;

    private int bought;

    /**
     * Constructor.
     *
     * @param client What is needed to buy a token.
     * @param http How the authorization server is reached.
     * @param clock What time it is.
     */
    public TokenPurchase(Client client, HttpClient http, Clock clock) {
        this.client = client;
        this.refresh = null;
        this.http = http;
        this.clock = clock;
    }

    /**
     * Constructor for a grant a person gave once, spent with its refresh token.
     *
     * @param refresh The grant.
     * @param http How the authorization server is reached.
     * @param clock What time it is.
     */
    public TokenPurchase(Refresh refresh, HttpClient http, Clock clock) {
        this.client = new Client(refresh.tokenUrl(), refresh.clientId(),
                refresh.clientSecret() == null ? "" : refresh.clientSecret(), "", null);
        this.refresh = refresh;
        this.refreshToken = refresh.refreshToken();
        this.http = http;
        this.clock = clock;
    }

    /**
     * Returns a valid access token, buying one when there is none or it is about to lapse.
     * <p>
     * Synchronized, which is the single flight: whoever finds no valid token buys one while the others wait,
     * and they then find it.
     *
     * @return The access token.
     * @throws Refused If the authorization server refused, could not be reached, or answered with no token.
     */
    public synchronized String current() throws Refused {
        if (token != null && clock.instant().isBefore(good)) {
            return token;
        }
        final Map<String, String> form = new LinkedHashMap<>();
        if (refresh != null) {
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", java.util.Objects.requireNonNull(refreshToken));
            form.put("client_id", client.clientId());
            if (!client.clientSecret().isEmpty()) {
                form.put("client_secret", client.clientSecret());
            }
        } else {
            form.put("grant_type", "client_credentials");
            form.put("client_id", client.clientId());
            form.put("client_secret", client.clientSecret());
            if (!client.scopes().isBlank()) {
                form.put("scope", client.scopes());
            }
            if (client.audience() != null) {
                form.put("audience", client.audience());
            }
        }
        final String body = form.entrySet().stream()
                .map(each -> URLEncoder.encode(each.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(each.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        final HttpResponse<String> answer;
        try {
            answer = http.send(HttpRequest.newBuilder(URI.create(client.tokenUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new Refused("the authorization server at " + host() + " could not be reached: " + Grants.cause(ex), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Refused("interrupted while buying a token from " + host(), ex);
        }
        final Object parsed;
        try {
            parsed = Json.parse(answer.body());
        } catch (RuntimeException ex) {
            throw new Refused("the authorization server at " + host() + " answered " + answer.statusCode()
                    + " with something that is not JSON", ex);
        }
        if (answer.statusCode() / 100 != 2) {
            // Its own error code, never its body whole: an error answer may echo what was sent.
            final String error = parsed instanceof Map<?, ?> document && document.get("error") != null
                    ? String.valueOf(document.get("error")) : "no error named";
            if (refresh != null && "invalid_grant".equals(error)) {
                // Revoked or expired at the service: the person has to grant it again, which is not a wrong key.
                throw new Ended("the grant at " + host() + " is no longer valid (revoked or expired); it needs"
                        + " authorizing again with 'sokar vault authorize'");
            }
            throw new Refused("the authorization server at " + host() + " refused these "
                    + (refresh != null ? "grant" : "client credentials") + " (" + answer.statusCode() + ", " + error + ")");
        }
        if (!(parsed instanceof Map<?, ?> document) || !(document.get("access_token") instanceof String access)
                || access.isBlank()) {
            throw new Refused("the authorization server at " + host() + " answered without an access token");
        }
        final Duration lasts = document.get("expires_in") instanceof Number seconds
                ? Duration.ofSeconds(seconds.longValue()) : UNSTATED;
        if (refresh != null && document.get("refresh_token") instanceof String next && !next.isBlank()
                && !next.equals(refreshToken)) {
            // Rotated: the old one is spent, and a vault still holding it would hold a dead grant.
            refreshToken = next;
            refresh.rotated().accept(next);
        }
        token = access;
        good = clock.instant().plus(lasts.compareTo(MARGIN.multipliedBy(2)) > 0 ? lasts.minus(MARGIN) : lasts.dividedBy(2));
        bought++;
        return access;
    }

    /**
     * Returns how many tokens were bought, for the test that proves the single flight.
     *
     * @return The count.
     */
    synchronized int bought() {
        return bought;
    }

    private String host() {
        return String.valueOf(URI.create(client.tokenUrl()).getHost());
    }
}
