package org.fuin.sokar.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DeviceGrant}: a person decides in a browser elsewhere while the machine waits.
 */
class DeviceGrantTest {

    private HttpServer server;

    private final Deque<String[]> tokenAnswers = new ArrayDeque<>();

    private final List<String> tokenBodies = new CopyOnWriteArrayList<>();

    private final List<Duration> slept = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/device", exchange -> answer(exchange, 200, """
                {"device_code":"dc-1","user_code":"WDJB-MJHT","verification_uri":"https://example.com/device",
                 "verification_uri_complete":"https://example.com/device?user_code=WDJB-MJHT","expires_in":900,"interval":5}
                """));
        server.createContext("/token", exchange -> {
            tokenBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final String[] next = tokenAnswers.isEmpty() ? new String[] { "400", "{\"error\":\"authorization_pending\"}" }
                    : tokenAnswers.poll();
            answer(exchange, Integer.parseInt(next[0]), next[1]);
        });
        server.createContext("/revoke", exchange -> {
            revokeBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            answer(exchange, revokeStatus, revokeStatus == 200 ? "{}" : "{\"error\":\"unsupported_token_type\"}");
        });
        server.start();
    }

    private final List<String> revokeBodies = new CopyOnWriteArrayList<>();

    private int revokeStatus = 200;

    private static void answer(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DeviceGrant grant() {
        final String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new DeviceGrant(new DeviceGrant.Client(base + "/device", base + "/token", "sokar-client", null,
                "repo offline_access", base + "/revoke"), HttpClient.newHttpClient());
    }

    @Test
    void showsTheCompleteLinkAndTheCode() throws Exception {
        final DeviceGrant.Started started = grant().start();

        assertThat(started.link()).isEqualTo("https://example.com/device?user_code=WDJB-MJHT");
        assertThat(started.userCode()).isEqualTo("WDJB-MJHT");
        assertThat(started.interval()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void waitsWhileThePersonDecidesAndSlowsDownWhenAsked() throws Exception {
        tokenAnswers.add(new String[] { "400", "{\"error\":\"authorization_pending\"}" });
        tokenAnswers.add(new String[] { "400", "{\"error\":\"slow_down\"}" });
        tokenAnswers.add(new String[] { "200",
                "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}" });
        final DeviceGrant grant = grant();

        final DeviceGrant.Outcome outcome = grant.await(grant.start(), slept::add);

        assertThat(outcome.state()).isEqualTo("granted");
        assertThat(outcome.refreshToken()).isEqualTo("rt-1");
        assertThat(slept).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(tokenBodies.getFirst()).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")
                .contains("device_code=dc-1").doesNotContain("client_secret");
    }

    @Test
    void saysWhenThePersonRefusedOrLetItExpire() throws Exception {
        tokenAnswers.add(new String[] { "400", "{\"error\":\"access_denied\"}" });
        final DeviceGrant grant = grant();
        assertThat(grant.await(grant.start(), slept::add).state()).isEqualTo("refused");

        tokenAnswers.add(new String[] { "400", "{\"error\":\"expired_token\"}" });
        assertThat(grant.await(grant.start(), slept::add).state()).isEqualTo("expired");
    }

    @Test
    void stopsWaitingWhenTheTimeRunsOut() throws Exception {
        final DeviceGrant grant = grant();
        final DeviceGrant.Started started = grant.start();

        final DeviceGrant.Outcome outcome = grant.await(new DeviceGrant.Started(started.deviceCode(),
                started.userCode(), started.link(), Duration.ofSeconds(12), Duration.ofSeconds(5)), slept::add);

        assertThat(outcome.state()).isEqualTo("expired");
        assertThat(slept).hasSize(3);
    }

    @Test
    void revokesTheRefreshTokenAtTheService() throws Exception {
        final String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Grants.revoke(new Grants.Service(base + "/token", "sokar-client", null, base + "/revoke"),
                HttpClient.newHttpClient(), "rt-1");

        assertThat(revokeBodies).singleElement().satisfies(body -> assertThat(body).contains("token=rt-1")
                .contains("token_type_hint=refresh_token").contains("client_id=sokar-client"));
    }

    @Test
    void aRefusedOrImpossibleRevocationIsSaid() {
        revokeStatus = 400;
        final String base = "http://127.0.0.1:" + server.getAddress().getPort();
        assertThatThrownBy(() -> Grants.revoke(new Grants.Service(base + "/token", "c", null, base + "/revoke"),
                HttpClient.newHttpClient(), "rt-1")).isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageContaining("refused to revoke the grant (400, unsupported_token_type)")
                .hasMessageNotContaining("rt-1");
        assertThatThrownBy(() -> Grants.revoke(new Grants.Service(base + "/token", "c", null, null),
                HttpClient.newHttpClient(), "rt-1")).hasMessageContaining("revocation_url");
    }

    @Test
    void aClientNeedsItsEndpointsAndHttps() {
        assertThatThrownBy(() -> DeviceGrant.Client.of("", Map.of("client_id", "x")))
                .hasMessageContaining("device_authorization_url");
        assertThatThrownBy(() -> DeviceGrant.Client.of("", Map.of("client_id", "x",
                "device_authorization_url", "http://a/d", "token_url", "https://a/t")))
                .hasMessageContaining("must be https");
        assertThat(DeviceGrant.Client.of("-", Map.of("client_id", "x", "device_authorization_url", "https://a/d",
                "token_url", "https://a/t")).clientSecret()).as("a public client").isNull();
        assertThatThrownBy(() -> DeviceGrant.Client.of("", Map.of("client_id", "x", "device_authorization_url",
                "https://a/d", "token_url", "https://a/t", "revocation_url", "http://a/r")))
                .hasMessageContaining("revocation URL must be https");
    }
}
