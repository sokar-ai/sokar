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
import java.util.Set;
import java.util.stream.Collectors;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What every kind of authorization a person grants once has in common, however it was granted: where its
 * refresh token is spent, and where it is revoked.
 * <p>
 * A device code ({@link DeviceGrant}) and an authorization code with PKCE ({@link CodeGrant}) differ only in
 * how the person says yes. What comes back is the same refresh token, kept the same way and spent the same
 * way by the broker.
 */
public final class Grants {

    /** The kinds of vault entry a person grants an authorization for. */
    public static final Set<String> KINDS = Set.of(DeviceGrant.KIND, CodeGrant.KIND);

    /**
     * Where a grant is spent and revoked.
     *
     * @param tokenUrl The token endpoint, https.
     * @param clientId The client's id.
     * @param clientSecret The client's secret, or {@code null} for a public client.
     * @param revocationUrl The revocation endpoint (RFC 7009), https, or {@code null} where the service has none.
     */
    public record Service(String tokenUrl, String clientId, @Nullable String clientSecret,
            @Nullable String revocationUrl) {
    }

    private Grants() {
    }

    /**
     * Returns whether a vault entry of this kind is one a person grants an authorization for.
     *
     * @param kind The entry's kind, or {@code null}.
     * @return true for {@link #KINDS}.
     */
    public static boolean isGrant(@Nullable String kind) {
        return kind != null && KINDS.contains(kind);
    }

    /**
     * Reads where a grant is spent and revoked from its entry.
     *
     * @param kind The entry's kind, one of {@link #KINDS}.
     * @param secret The entry's value.
     * @param settings The entry's settings.
     * @return The service.
     * @throws IllegalArgumentException If the kind is not a grant's, a setting is missing, or an endpoint is not https.
     */
    public static Service service(@Nullable String kind, String secret, Map<String, String> settings) {
        if (DeviceGrant.KIND.equals(kind)) {
            final DeviceGrant.Client client = DeviceGrant.Client.of(secret, settings);
            return new Service(client.tokenUrl(), client.clientId(), client.clientSecret(), client.revocationUrl());
        }
        if (CodeGrant.KIND.equals(kind)) {
            final CodeGrant.Client client = CodeGrant.Client.of(secret, settings);
            return new Service(client.tokenUrl(), client.clientId(), client.clientSecret(), client.revocationUrl());
        }
        throw new IllegalArgumentException("'" + kind + "' is not a kind a person grants an authorization for");
    }

    /**
     * Reads a revocation URL setting, which is optional and must be https where given.
     *
     * @param settings The entry's settings.
     * @return The URL, or {@code null}.
     */
    static @Nullable String revocationUrl(Map<String, String> settings) {
        final String revocation = settings.get("revocation_url");
        if (revocation != null && !revocation.startsWith("https://")) {
            throw new IllegalArgumentException("the revocation URL must be https, not '" + revocation + "'");
        }
        return revocation;
    }

    /**
     * Tells the service a grant is finished with, so the refresh token Sokar held stops working there too
     * (RFC 7009).
     * <p>
     * The service answers 200 for a token it no longer knows as well, which is the wanted state: revoking
     * twice is not a failure.
     *
     * @param service Where to revoke it.
     * @param http How the service is reached.
     * @param refreshToken The refresh token in force.
     * @throws TokenPurchase.Refused If the service has no revocation endpoint, refuses, or cannot be reached.
     */
    public static void revoke(Service service, HttpClient http, String refreshToken) throws TokenPurchase.Refused {
        final String url = service.revocationUrl();
        if (url == null) {
            throw new TokenPurchase.Refused("no revocation endpoint is declared for this service; set"
                    + " 'revocation_url' on the entry, or revoke the grant in the service's own settings");
        }
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("token", refreshToken);
        form.put("token_type_hint", "refresh_token");
        form.put("client_id", service.clientId());
        if (service.clientSecret() != null) {
            form.put("client_secret", service.clientSecret());
        }
        final HttpResponse<String> answer = send(http, url, form);
        if (answer.statusCode() / 100 != 2) {
            throw new TokenPurchase.Refused("the service at " + host(url) + " refused to revoke the grant ("
                    + answer.statusCode() + ", " + error(answer.body()) + ")");
        }
    }

    /**
     * Posts a form, as every OAuth endpoint takes one.
     *
     * @param http How the service is reached.
     * @param url Where.
     * @param form What.
     * @return The answer, whatever its status.
     * @throws TokenPurchase.Refused If the service cannot be reached.
     */
    static HttpResponse<String> send(HttpClient http, String url, Map<String, String> form)
            throws TokenPurchase.Refused {
        final String body = form.entrySet().stream()
                .map(each -> URLEncoder.encode(each.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(each.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new TokenPurchase.Refused("the service at " + host(url) + " could not be reached: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TokenPurchase.Refused("interrupted while asking " + host(url), ex);
        }
    }

    /**
     * Returns the error code an answer names - its own code, never its body whole, since an error answer
     * may echo what was sent.
     *
     * @param body The answer's body.
     * @return The code, or "no error named".
     */
    static String error(String body) {
        try {
            if (Json.parse(body) instanceof Map<?, ?> document && document.get("error") != null) {
                return String.valueOf(document.get("error"));
            }
        } catch (RuntimeException ex) {
            // Not JSON; the status says enough.
        }
        return "no error named";
    }

    static String host(String url) {
        return String.valueOf(URI.create(url).getHost());
    }
}
