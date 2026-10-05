package org.fuin.sokar.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for where a key goes: a header nobody else uses, or the URL - and that neither reaches the log.
 */
class VaultProxyKeyPlacementTest {

    private static final String PHANTOM = "sokar_phantom_1234";

    private static final String REAL = "AIza-real-key-5678";

    private HttpServer upstream;

    private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();

    private final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            final Map<String, String> headers = new java.util.LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            headers.put(":target", exchange.getRequestURI().toString());
            received.add(headers);
            final byte[] payload = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    private VaultProxy proxy(Path socket, String header, String query) {
        final VaultProxy proxy = new VaultProxy(socket, "http://127.0.0.1:" + upstream.getAddress().getPort(),
                presented -> PHANTOM.equals(presented) ? new TokenExchange.Granted(REAL) : new TokenExchange.Rejected(),
                header, "", query, logged::add);
        Thread.ofPlatform().daemon().start(proxy);
        return proxy;
    }

    private static String send(Path socket, String request) throws IOException {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.write(java.nio.ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final InputStream in = java.nio.channels.Channels.newInputStream(channel);
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void aTargetThatIsNotAUriIsRefusedWithoutTheRealKeyAppearingAnywhere(@TempDir Path dir) throws Exception {

        // The key went into the address before the address was parsed, so the parser's complaint repeated it - and
        // a thread's uncaught exception goes to stderr, which is the broker's log.
        final java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
        final java.io.PrintStream before = System.err;
        final String answer;
        System.setErr(new java.io.PrintStream(stderr, true, StandardCharsets.UTF_8));
        try {
            final Path socket = dir.resolve("vault.sock");
            proxy(socket, "x-goog-api-key", "key");
            answer = send(socket, "GET /v1/models?f={a}&key=" + PHANTOM + " HTTP/1.1\r\nHost: x\r\n\r\n");
            Thread.sleep(200);
        } finally {
            System.setErr(before);
        }

        assertThat(answer).startsWith("HTTP/1.1 400").doesNotContain(REAL);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).doesNotContain(REAL);
        assertThat(String.join("\n", logged)).doesNotContain(REAL);
        assertThat(received).isEmpty();
    }

    @Test
    void aReasonFromElsewhereIsSaidAsValidJson(@TempDir Path dir) throws Exception {

        // The reason was written into the JSON as it came, and it can carry an authorization server's own words.
        final Path socket = dir.resolve("vault.sock");
        final VaultProxy proxy = new VaultProxy(socket, "http://127.0.0.1:" + upstream.getAddress().getPort(),
                presented -> new TokenExchange.Unavailable("the server said \"no\" \\ twice"),
                "x-api-key", "", null, logged::add);
        Thread.ofPlatform().daemon().start(proxy);

        final String answer = send(socket, "GET /v1/models HTTP/1.1\r\nx-api-key: " + PHANTOM + "\r\n\r\n");
        final String body = answer.substring(answer.indexOf("\r\n\r\n") + 4);

        assertThat(answer).startsWith("HTTP/1.1 503");
        assertThat(org.fuin.sokar.wire.Json.parse(body)).asString().contains("the server said \"no\" \\ twice");
    }

    @Test
    void aKeyInTheRoutesOwnHeaderIsThisTasksToken(@TempDir Path dir) throws IOException {

        // Read from the code on 2026-09-09: only four header names were looked at, so a key where
        // Google says to put it was refused as "not this task's token" - which was false.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-goog-api-key", null)) {
            final String response = send(socket, "GET /v1/models HTTP/1.1\r\nx-goog-api-key: " + PHANTOM + "\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 200");
            assertThat(received).singleElement().satisfies(request -> {
                assertThat(request.get("x-goog-api-key")).isEqualTo(REAL);
                assertThat(request.values()).doesNotContain(PHANTOM);
            });
        }
    }

    @Test
    void aKeyThatBelongsInTheUrlIsTakenOutAndTheRealOnePutIn(@TempDir Path dir) throws IOException {

        // Forwarded as it came, the phantom travelled upstream beside the real key.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "key")) {
            final String response = send(socket,
                    "GET /v1/models?alt=json&key=" + PHANTOM + " HTTP/1.1\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 200");
            assertThat(received).singleElement().satisfies(request -> {
                assertThat(request.get(":target")).isEqualTo("/v1/models?alt=json&key=" + REAL);
                assertThat(request).as("in the URL and in no header").doesNotContainKey("x-api-key");
                assertThat(request.toString()).doesNotContain(PHANTOM);
            });
        }
    }

    @Test
    void noValueInTheQueryStringReachesTheLog(@TempDir Path dir) throws IOException {

        // A key in the URL is a key in the log: every line names the request's target.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "key")) {
            send(socket, "GET /v1/models?key=" + PHANTOM + "&alt=json HTTP/1.1\r\n\r\n");
            send(socket, "GET /v1/models?key=somebody-elses HTTP/1.1\r\n\r\n");
        }

        assertThat(logged).isNotEmpty();
        assertThat(logged).allSatisfy(line -> assertThat(line).doesNotContain(PHANTOM)
                .doesNotContain("somebody-elses").doesNotContain(REAL).doesNotContain("json"));
        assertThat(logged.getFirst()).contains("/v1/models?key=…&alt=…");
    }

    @Test
    void aTargetThatNamesAnotherHostIsRefusedAndTheKeyGoesNowhere(@TempDir Path dir) throws Exception {

        // The address was the upstream with the agent's target appended: '@localhost:<port>/x' made the upstream's
        // host the user part and sent the real key to a host the agent chose; '.attacker.example/x' does the same.
        final Path socket = dir.resolve("vault.sock");
        proxy(socket, "x-goog-api-key", "");
        final String answer = send(socket, "GET @localhost:" + upstream.getAddress().getPort()
                + "/v1/models HTTP/1.1\r\nHost: x\r\nx-goog-api-key: " + PHANTOM + "\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 400").doesNotContain(REAL);
        assertThat(received).as("nothing was forwarded").isEmpty();
    }
}
