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
 * Tests for a task that holds more than one credential: the token picks the route.
 */
class VaultProxyRoutesTest {

    private static final String AGENT_TOKEN = "sokar_agent_phantom";

    private static final String SEARCH_TOKEN = "sokar_search_phantom";

    private static final String AGENT_KEY = "sk-agent-real-key";

    private static final String SEARCH_KEY = "BSA-search-real-key";

    private HttpServer provider;

    private HttpServer search;

    private final List<Map<String, String>> atProvider = new CopyOnWriteArrayList<>();

    private final List<Map<String, String>> atSearch = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startUpstreams() throws IOException {
        provider = upstream(atProvider);
        search = upstream(atSearch);
    }

    @AfterEach
    void stopUpstreams() {
        provider.stop(0);
        search.stop(0);
    }

    private static HttpServer upstream(List<Map<String, String>> received) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
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
        server.start();
        return server;
    }

    private VaultProxy proxy(Path socket) {
        // What vault serve does: each token is scoped to its credential, and the grant says which.
        final TokenExchange exchange = presented -> switch (presented) {
            case AGENT_TOKEN -> new TokenExchange.Granted(AGENT_KEY, "anthropic");
            case SEARCH_TOKEN -> new TokenExchange.Granted(SEARCH_KEY, "search");
            default -> new TokenExchange.Rejected();
        };
        final VaultProxy proxy = new VaultProxy(socket, "http://127.0.0.1:" + provider.getAddress().getPort(), exchange,
                "x-api-key", "", null,
                Map.of("search", new VaultProxy.Route("http://127.0.0.1:" + search.getAddress().getPort(),
                        "X-Subscription-Token", "", null)), line -> { });
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
    void eachTokenReachesItsOwnServiceWithItsOwnKey(@TempDir Path dir) throws IOException {
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket)) {
            assertThat(send(socket, "POST /v1/messages HTTP/1.1\r\nx-api-key: " + AGENT_TOKEN
                    + "\r\ncontent-length: 0\r\n\r\n")).startsWith("HTTP/1.1 200");
            assertThat(send(socket, "GET /res/v1/web/search?q=sokar HTTP/1.1\r\nX-Subscription-Token: "
                    + SEARCH_TOKEN + "\r\n\r\n")).startsWith("HTTP/1.1 200");
        }

        assertThat(atProvider).singleElement().satisfies(request -> {
            assertThat(request.get("x-api-key")).isEqualTo(AGENT_KEY);
            assertThat(request).doesNotContainKey("x-subscription-token");
        });
        assertThat(atSearch).singleElement().satisfies(request -> {
            assertThat(request.get("x-subscription-token")).isEqualTo(SEARCH_KEY);
            assertThat(request.get(":target")).isEqualTo("/res/v1/web/search?q=sokar");
            assertThat(request).doesNotContainKey("x-api-key");
        });
    }

    @Test
    void aTokenCannotCarryItsKeyToAnotherService(@TempDir Path dir) throws IOException {

        // The search token presented in the provider's header, on the provider's path: it still goes to
        // search, with the search key, and the provider never sees it.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket)) {
            send(socket, "POST /v1/messages HTTP/1.1\r\nx-api-key: " + SEARCH_TOKEN + "\r\ncontent-length: 0\r\n\r\n");
        }

        assertThat(atProvider).as("the provider never saw the search credential").isEmpty();
        assertThat(atSearch).singleElement().satisfies(request ->
                assertThat(request.get("x-subscription-token")).isEqualTo(SEARCH_KEY));
    }
}
