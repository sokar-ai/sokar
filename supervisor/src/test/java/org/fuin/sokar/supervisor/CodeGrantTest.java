package org.fuin.sokar.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CodeGrant}: a person answers in a browser, and the answer comes back to this machine's
 * loopback.
 */
class CodeGrantTest {

    private HttpServer service;

    private final List<String> tokenBodies = new CopyOnWriteArrayList<>();

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/token", exchange -> {
            tokenBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] payload = "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        service.start();
    }

    @AfterEach
    void stop() {
        service.stop(0);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private CodeGrant grant(int port) {
        return new CodeGrant(new CodeGrant.Client("https://auth.example.invalid/authorize",
                "http://127.0.0.1:" + service.getAddress().getPort() + "/token", "sokar-client", null,
                "repo offline_access", port, null), http);
    }

    private static Map<String, String> query(String link) {
        final Map<String, String> query = new LinkedHashMap<>();
        for (final String pair : URI.create(link).getRawQuery().split("&")) {
            final String[] parts = pair.split("=", 2);
            query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return query;
    }

    private int answer(int port, String query) throws Exception {
        // A browser's request: its own connection, nothing pooled from another test's listener.
        try (HttpClient browser = HttpClient.newHttpClient()) {
            return browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/callback?" + query))
                    .build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @Test
    void theLinkCarriesTheLoopbackRedirectAndAnS256Challenge() throws Exception {
        final int port = freePort();
        final CodeGrant grant = grant(port);
        try {
            final Map<String, String> query = query(grant.start(Duration.ofSeconds(5)).link());

            assertThat(query).containsEntry("response_type", "code").containsEntry("client_id", "sokar-client")
                    .containsEntry("redirect_uri", "http://127.0.0.1:" + port + "/callback")
                    .containsEntry("scope", "repo offline_access").containsEntry("code_challenge_method", "S256")
                    .containsKeys("state", "code_challenge");
            assertThat(query.get("code_challenge")).doesNotContain("=").hasSize(43);
        } finally {
            grant.stop();
        }
    }

    @Test
    void theAnswerComingBackIsExchangedWithTheVerifierForTheGrant() throws Exception {
        final int port = freePort();
        final CodeGrant grant = grant(port);
        final CodeGrant.Started started = grant.start(Duration.ofSeconds(10));
        final String state = query(started.link()).get("state");
        final String challenge = query(started.link()).get("code_challenge");
        final var pool = Executors.newSingleThreadExecutor();
        try {
            final Future<DeviceGrant.Outcome> waiting = pool.submit(() -> grant.await(started));

            assertThat(answer(port, "code=c-1&state=wrong")).as("a stale tab is not the answer").isEqualTo(400);
            assertThat(answer(port, "code=c-1&state=" + state)).isEqualTo(200);
            final DeviceGrant.Outcome outcome = waiting.get();

            assertThat(outcome.state()).isEqualTo("granted");
            assertThat(outcome.refreshToken()).isEqualTo("rt-1");
            final String body = tokenBodies.getFirst();
            assertThat(body).contains("grant_type=authorization_code").contains("code=c-1")
                    .contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A" + port + "%2Fcallback");
            final String verifier = URLDecoder.decode(body.replaceAll(".*code_verifier=([^&]*).*", "$1"),
                    StandardCharsets.UTF_8);
            assertThat(CodeGrant.challenge(verifier)).as("the verifier is the one the challenge was made from")
                    .isEqualTo(challenge);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRefusalIsSaidAndNothingIsExchanged() throws Exception {
        final int port = freePort();
        final CodeGrant grant = grant(port);
        final CodeGrant.Started started = grant.start(Duration.ofSeconds(10));
        final var pool = Executors.newSingleThreadExecutor();
        try {
            final Future<DeviceGrant.Outcome> waiting = pool.submit(() -> grant.await(started));
            answer(port, "error=access_denied&state=" + query(started.link()).get("state"));

            assertThat(waiting.get().state()).isEqualTo("refused");
            assertThat(tokenBodies).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void nobodyAnsweringInTimeExpiresAndFreesThePort() throws Exception {
        final int port = freePort();
        final CodeGrant grant = grant(port);

        assertThat(grant.await(grant.start(Duration.ofMillis(200))).state()).isEqualTo("expired");
        try (ServerSocket again = new ServerSocket(port, 0, java.net.InetAddress.getLoopbackAddress())) {
            assertThat(again.getLocalPort()).isEqualTo(port);
        }
    }

    @Test
    void aTakenPortIsSaidWithWhatToChange() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            assertThatThrownBy(() -> grant(taken.getLocalPort()).start(Duration.ofSeconds(1)))
                    .isInstanceOf(TokenPurchase.Refused.class).hasMessageContaining("redirect_port");
        }
    }

    @Test
    void aClientNeedsItsEndpointsHttpsAndAPort() {
        assertThatThrownBy(() -> CodeGrant.Client.of("", Map.of("client_id", "x")))
                .hasMessageContaining("authorization_url");
        assertThatThrownBy(() -> CodeGrant.Client.of("", Map.of("client_id", "x", "authorization_url", "http://a/a",
                "token_url", "https://a/t"))).hasMessageContaining("must be https");
        assertThatThrownBy(() -> CodeGrant.Client.of("", Map.of("client_id", "x", "authorization_url", "https://a/a",
                "token_url", "https://a/t", "redirect_port", "80"))).hasMessageContaining("between 1024");
        final CodeGrant.Client client = CodeGrant.Client.of("-", Map.of("client_id", "x",
                "authorization_url", "https://a/a", "token_url", "https://a/t"));
        assertThat(client.clientSecret()).as("a public client").isNull();
        assertThat(client.redirectUri()).isEqualTo("http://127.0.0.1:" + CodeGrant.DEFAULT_PORT + "/callback");
        assertThat(Grants.service(CodeGrant.KIND, "-", Map.of("client_id", "x", "authorization_url", "https://a/a",
                "token_url", "https://a/t", "revocation_url", "https://a/r")).revocationUrl()).isEqualTo("https://a/r");
    }
}
