package org.fuin.sokar.supervisor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * An authorization a person grants once through a redirect: the OAuth authorization code grant with PKCE
 * (RFC 6749, RFC 7636), for a service that offers no device code.
 * <p>
 * <strong>The redirect lands on the machine's own loopback</strong> (RFC 8252): this listens on
 * {@code 127.0.0.1} at the port the entry names, for the one answer it is waiting for. A person at another
 * machine forwards that port through the ssh connection they already hold, so the browser's redirect to
 * {@code 127.0.0.1} comes back down the tunnel. Decided on 2026-09-29: the device code first,
 * this second.
 * <p>
 * What comes back is the same refresh token as from {@link DeviceGrant}, kept and spent the same way.
 */
public final class CodeGrant {

    /** The kind a vault entry for such a service carries. */
    public static final String KIND = "oauth-code";

    /** The port the redirect comes back to when the entry names none. */
    public static final int DEFAULT_PORT = 9420;

    /** The path the redirect comes back to. */
    public static final String CALLBACK = "/callback";

    /**
     * What the person is to be shown, and where the answer is awaited.
     *
     * @param link The authorization link, whole.
     * @param port The loopback port the redirect comes back to, to forward from where the browser is.
     * @param expiresIn How long the answer is waited for.
     */
    public record Started(String link, int port, Duration expiresIn) {
    }

    /**
     * What is needed to ask.
     *
     * @param authorizationUrl The authorization endpoint, https.
     * @param tokenUrl The token endpoint, https.
     * @param clientId The client's id.
     * @param clientSecret The client's secret, or {@code null} for a public client.
     * @param scopes The scopes, space-separated, or empty.
     * @param port The loopback port the redirect comes back to; the service must know the redirect URI.
     * @param revocationUrl The revocation endpoint (RFC 7009), https, or {@code null} where the service has none.
     */
    public record Client(String authorizationUrl, String tokenUrl, String clientId, @Nullable String clientSecret,
            String scopes, int port, @Nullable String revocationUrl) {

        /**
         * Reads a client from a vault entry's secret and settings.
         *
         * @param secret The entry's value: the client secret, or {@code -} for a public client.
         * @param settings The entry's settings.
         * @return The client.
         * @throws IllegalArgumentException If a setting it needs is missing, an endpoint is not https, or the
         *         port is not one.
         */
        public static Client of(String secret, Map<String, String> settings) {
            final String authorization = settings.get("authorization_url");
            final String token = settings.get("token_url");
            final String id = settings.get("client_id");
            if (authorization == null || token == null || id == null) {
                throw new IllegalArgumentException("an " + KIND + " entry needs the settings authorization_url,"
                        + " token_url and client_id");
            }
            if (!Grants.secure(authorization) || !Grants.secure(token)) {
                throw new IllegalArgumentException("the authorization and token URLs must be https, or http on"
                        + " loopback");
            }
            final int port;
            try {
                port = Integer.parseInt(settings.getOrDefault("redirect_port", String.valueOf(DEFAULT_PORT)));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("redirect_port must be a port number", ex);
            }
            if (port < 1024 || port > 65535) {
                throw new IllegalArgumentException("redirect_port must be between 1024 and 65535, not " + port);
            }
            return new Client(authorization, token, id, secret.isBlank() || "-".equals(secret) ? null : secret,
                    settings.getOrDefault("scopes", ""), port, Grants.revocationUrl(settings));
        }

        /**
         * Returns the redirect URI the service must have registered for this client.
         *
         * @return The loopback URI.
         */
        public String redirectUri() {
            return "http://127.0.0.1:" + port + CALLBACK;
        }
    }

    private final Client client;

    private final HttpClient http;

    private final SecureRandom random = new SecureRandom();

    private @Nullable HttpServer listener;

    private @Nullable String verifier;

    private @Nullable String state;

    private final CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();

    private final java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Constructor.
     *
     * @param client What is needed to ask.
     * @param http How the service is reached.
     */
    public CodeGrant(Client client, HttpClient http) {
        this.client = client;
        this.http = http;
    }

    /**
     * Starts listening for the redirect and returns the link the person opens.
     *
     * @param expiresIn How long the answer is to be waited for.
     * @return What to show.
     * @throws TokenPurchase.Refused If the port is taken.
     */
    public synchronized Started start(Duration expiresIn) throws TokenPurchase.Refused {
        if (listener != null) {
            throw new IllegalStateException("already started");
        }
        verifier = token(32);
        state = token(16);
        final HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), client.port()), 0);
        } catch (IOException ex) {
            throw new TokenPurchase.Refused("port " + client.port() + " on this machine is taken, so the answer"
                    + " cannot come back to it; set another 'redirect_port' on the entry, registered with the service", ex);
        }
        server.createContext(CALLBACK, this::callback);
        server.start();
        listener = server;
        final Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", client.clientId());
        query.put("redirect_uri", client.redirectUri());
        if (!client.scopes().isBlank()) {
            query.put("scope", client.scopes());
        }
        query.put("state", state);
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");
        final String link = client.authorizationUrl() + (client.authorizationUrl().contains("?") ? "&" : "?")
                + query.entrySet().stream().map(each -> encode(each.getKey()) + "=" + encode(each.getValue()))
                        .collect(Collectors.joining("&"));
        return new Started(link, client.port(), expiresIn);
    }

    /**
     * Waits for the person's answer, and exchanges the code it carries for the grant.
     *
     * @param started What {@link #start(Duration)} answered.
     * @return What was decided.
     * @throws TokenPurchase.Refused If the service answers something the flow does not know, or cannot be reached.
     * @throws InterruptedException If interrupted while waiting.
     */
    public DeviceGrant.Outcome await(Started started) throws TokenPurchase.Refused, InterruptedException {
        final Map<String, String> query;
        try {
            query = answer.get(started.expiresIn().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return new DeviceGrant.Outcome("expired", null, null, null);
        } catch (ExecutionException ex) {
            throw new IllegalStateException(ex.getCause());
        } finally {
            stop();
        }
        final String error = query.get("error");
        if (error != null) {
            if ("access_denied".equals(error)) {
                return new DeviceGrant.Outcome("refused", null, null, null);
            }
            throw new TokenPurchase.Refused("the service at " + Grants.host(client.authorizationUrl())
                    + " answered '" + error + "' instead of a code");
        }
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", query.getOrDefault("code", ""));
        form.put("redirect_uri", client.redirectUri());
        form.put("client_id", client.clientId());
        form.put("code_verifier", java.util.Objects.requireNonNull(verifier));
        if (client.clientSecret() != null) {
            form.put("client_secret", client.clientSecret());
        }
        final HttpResponse<String> exchanged = Grants.send(http, client.tokenUrl(), form);
        if (exchanged.statusCode() / 100 != 2) {
            throw new TokenPurchase.Refused("the service at " + Grants.host(client.tokenUrl())
                    + " refused the code (" + exchanged.statusCode() + ", " + Grants.error(exchanged.body()) + ")");
        }
        final Object parsed;
        try {
            parsed = Json.parse(exchanged.body());
        } catch (RuntimeException ex) {
            throw new TokenPurchase.Refused("the service at " + Grants.host(client.tokenUrl())
                    + " answered with something that is not JSON", ex);
        }
        if (!(parsed instanceof Map<?, ?> document) || !(document.get("access_token") instanceof String access)
                || access.isBlank()) {
            throw new TokenPurchase.Refused("the service at " + Grants.host(client.tokenUrl())
                    + " granted without an access token");
        }
        return new DeviceGrant.Outcome("granted",
                document.get("refresh_token") instanceof String refresh && !refresh.isBlank() ? refresh : null, access,
                document.get("expires_in") instanceof Number seconds ? Duration.ofSeconds(seconds.longValue())
                        : null);
    }

    /** Stops listening; the answer is either in or no longer wanted. */
    public synchronized void stop() {
        if (listener != null) {
            listener.stop(0);
        }
    }

    private void callback(HttpExchange exchange) throws IOException {
        final Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
        final String status;
        final String said;
        final boolean theAnswer;
        if (!java.util.Objects.equals(state, query.get("state"))) {
            // Not the answer to this question: a stale tab, or someone else's request. Ignored, still waiting.
            status = "400";
            said = "This is not the authorization Sokar is waiting for.";
            theAnswer = false;
        } else if (answered.compareAndSet(false, true)) {
            theAnswer = true;
            status = "200";
            said = query.containsKey("error") ? "Sokar was told no. This tab can be closed."
                    : "Sokar has the answer. This tab can be closed.";
        } else {
            theAnswer = false;
            status = "409";
            said = "Sokar already has an answer to this question.";
        }
        final byte[] payload = ("<!doctype html><meta charset=utf-8><title>Sokar</title><p>" + said)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(Integer.parseInt(status), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        } finally {
            // Handed over only once the page is sent: the wait stops the listener, and a browser cut off
            // mid-answer shows the person an error for an authorization that worked.
            if (theAnswer) {
                answer.complete(query);
            }
        }
    }

    private static Map<String, String> parse(@Nullable String raw) {
        final Map<String, String> query = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (final String pair : raw.split("&")) {
            final int equals = pair.indexOf('=');
            final String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            final String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            query.putIfAbsent(key, value);
        }
        return query;
    }

    private String token(int bytes) {
        final byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Returns the S256 challenge of a verifier (RFC 7636).
     *
     * @param verifier The verifier.
     * @return The challenge.
     */
    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
