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
}
