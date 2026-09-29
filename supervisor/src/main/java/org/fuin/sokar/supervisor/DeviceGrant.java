package org.fuin.sokar.supervisor;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * An authorization a person grants once, in any browser, while the machine waits: the OAuth device
 * authorization grant (RFC 8628).
 * <p>
 * <strong>Chosen first because it needs nobody near the machine.</strong> Sokar shows a URL and a short code;
 * the person opens it wherever they are, and this polls the token endpoint until they have decided. Nothing
 * comes back to the machine through a redirect, so no listener and no tunnel is needed. What comes back is a
 * refresh token, which the vault keeps and the broker spends: no task ever holds it.
 */
public final class DeviceGrant {

    /** The kind a vault entry for such a service carries. */
    public static final String KIND = "oauth-device";

    /**
     * What the service answered when asked to start: what the person is shown, and what this polls with.
     *
     * @param deviceCode What the poll presents, never shown.
     * @param userCode What the person types, if the link does not carry it.
     * @param link Where the person goes: the complete link when the service gives one, else the plain one.
     * @param expiresIn How long the person has.
     * @param interval How long to wait between polls.
     */
    public record Started(String deviceCode, String userCode, String link, Duration expiresIn, Duration interval) {
    }

    /**
     * What a person decided, or that they did not in time.
     *
     * @param state {@code granted}, {@code refused} or {@code expired}.
     * @param refreshToken The refresh token, when granted.
     * @param accessToken The first access token, when granted.
     * @param expiresIn How long that access token lasts, when granted.
     */
    public record Outcome(String state, @Nullable String refreshToken, @Nullable String accessToken,
            @Nullable Duration expiresIn) {
    }

    /**
     * What is needed to ask.
     *
     * @param deviceUrl The device authorization endpoint, https.
     * @param tokenUrl The token endpoint, https.
     * @param clientId The client's id.
     * @param clientSecret The client's secret, or {@code null} for a public client.
     * @param scopes The scopes, space-separated, or empty.
     */
    public record Client(String deviceUrl, String tokenUrl, String clientId, @Nullable String clientSecret,
            String scopes) {

        /**
         * Reads a client from a vault entry's secret and settings.
         *
         * @param secret The entry's value: the client secret, or empty for a public client.
         * @param settings The entry's settings.
         * @return The client.
         * @throws IllegalArgumentException If a setting it needs is missing or an endpoint is not https.
         */
        public static Client of(String secret, Map<String, String> settings) {
            final String device = settings.get("device_authorization_url");
            final String token = settings.get("token_url");
            final String id = settings.get("client_id");
            if (device == null || token == null || id == null) {
                throw new IllegalArgumentException("an " + KIND + " entry needs the settings device_authorization_url,"
                        + " token_url and client_id");
            }
            if (!device.startsWith("https://") || !token.startsWith("https://")) {
                throw new IllegalArgumentException("the device authorization and token URLs must be https");
            }
            return new Client(device, token, id, secret.isBlank() || "-".equals(secret) ? null : secret,
                    settings.getOrDefault("scopes", ""));
        }
    }

    /** Waits between polls; a seam so tests need not sleep. */
    public interface Sleeper {

        /**
         * Waits.
         *
         * @param duration How long.
         * @throws InterruptedException If interrupted.
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Client client;

    private final HttpClient http;

    /**
     * Constructor.
     *
     * @param client What is needed to ask.
     * @param http How the service is reached.
     */
    public DeviceGrant(Client client, HttpClient http) {
        this.client = client;
        this.http = http;
    }

    /**
     * Asks the service to start: what the person is to be shown.
     *
     * @return What to show, and what to poll with.
     * @throws TokenPurchase.Refused If the service refuses or cannot be reached.
     */
    public Started start() throws TokenPurchase.Refused {
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", client.clientId());
        if (!client.scopes().isBlank()) {
            form.put("scope", client.scopes());
        }
        final Map<?, ?> answer = post(client.deviceUrl(), form, true);
        final String device = text(answer, "device_code");
        final String user = text(answer, "user_code");
        final String complete = text(answer, "verification_uri_complete");
        final String plain = text(answer, "verification_uri");
        if (device == null || user == null || (complete == null && plain == null)) {
            throw new TokenPurchase.Refused("the service at " + host(client.deviceUrl())
                    + " answered without a device code, a user code or a link");
        }
        return new Started(device, user, complete != null ? complete : java.util.Objects.requireNonNull(plain),
                seconds(answer, "expires_in", Duration.ofMinutes(10)), seconds(answer, "interval", Duration.ofSeconds(5)));
    }

    /**
     * Polls until the person decided or the time ran out.
     *
     * @param started What {@link #start()} answered.
     * @param sleeper How to wait between polls.
     * @return What was decided.
     * @throws TokenPurchase.Refused If the service answers something the flow does not know, or cannot be reached.
     * @throws InterruptedException If interrupted while waiting.
     */
    public Outcome await(Started started, Sleeper sleeper) throws TokenPurchase.Refused, InterruptedException {
        Duration interval = started.interval();
        Duration waited = Duration.ZERO;
        while (waited.compareTo(started.expiresIn()) < 0) {
            sleeper.sleep(interval);
            waited = waited.plus(interval);
            final Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("device_code", started.deviceCode());
            form.put("client_id", client.clientId());
            if (client.clientSecret() != null) {
                form.put("client_secret", client.clientSecret());
            }
            final Map<?, ?> answer = post(client.tokenUrl(), form, false);
            final String error = text(answer, "error");
            if (error == null) {
                final String access = text(answer, "access_token");
                if (access == null) {
                    throw new TokenPurchase.Refused("the service at " + host(client.tokenUrl())
                            + " granted without an access token");
                }
                return new Outcome("granted", text(answer, "refresh_token"), access,
                        seconds(answer, "expires_in", TokenPurchase.UNSTATED));
            }
            switch (error) {
                case "authorization_pending" -> {
                    // The person has not decided yet.
                }
                case "slow_down" -> interval = interval.plusSeconds(5);
                case "access_denied" -> {
                    return new Outcome("refused", null, null, null);
                }
                case "expired_token" -> {
                    return new Outcome("expired", null, null, null);
                }
                default -> throw new TokenPurchase.Refused("the service at " + host(client.tokenUrl())
                        + " answered '" + error + "' while the person was deciding");
            }
        }
        return new Outcome("expired", null, null, null);
    }

    private Map<?, ?> post(String url, Map<String, String> form, boolean mustSucceed) throws TokenPurchase.Refused {
        final String body = form.entrySet().stream()
                .map(each -> URLEncoder.encode(each.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(each.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        final HttpResponse<String> answer;
        try {
            answer = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new TokenPurchase.Refused("the service at " + host(url) + " could not be reached: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TokenPurchase.Refused("interrupted while asking " + host(url), ex);
        }
        final Object parsed;
        try {
            parsed = Json.parse(answer.body());
        } catch (RuntimeException ex) {
            throw new TokenPurchase.Refused("the service at " + host(url) + " answered " + answer.statusCode()
                    + " with something that is not JSON", ex);
        }
        if (!(parsed instanceof Map<?, ?> document)) {
            throw new TokenPurchase.Refused("the service at " + host(url) + " answered something that is not an object");
        }
        // A token endpoint answers a pending grant with 400 and an error; that is the flow, not a failure.
        if (mustSucceed && answer.statusCode() / 100 != 2) {
            throw new TokenPurchase.Refused("the service at " + host(url) + " refused to start ("
                    + answer.statusCode() + ", " + (document.get("error") == null ? "no error named"
                            : document.get("error")) + ")");
        }
        return document;
    }

    private static @Nullable String text(Map<?, ?> document, String key) {
        return document.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private static Duration seconds(Map<?, ?> document, String key, Duration otherwise) {
        return document.get(key) instanceof Number number ? Duration.ofSeconds(number.longValue()) : otherwise;
    }

    private static String host(String url) {
        return String.valueOf(URI.create(url).getHost());
    }
}
