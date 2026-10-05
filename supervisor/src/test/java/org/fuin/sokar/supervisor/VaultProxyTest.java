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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link VaultProxy}, against a fake provider.
 * <p>
 * The swap is only believable if it is checked from the far side, so the fake upstream records
 * exactly what reached it. That is what tells a real credential injection apart from a request
 * that merely succeeded.
 */
class VaultProxyTest {

    private static final String PHANTOM = "sokar_pt_thisIsTheTasksToken";

    private static final String REAL = "sk-ant-the-real-credential";

    private HttpServer upstream;

    private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();

    private final AtomicReference<String> body = new AtomicReference<>("");

    private final AtomicReference<byte[]> rawBody = new AtomicReference<>(new byte[0]);

    private AtomicReference<String> responseBody;

    /** Set instead of {@link #responseBody} when the answer must be exact bytes. */
    private AtomicReference<byte[]> rawResponse;

    /** What the fake provider declares its answer to be. A real one always declares something. */
    private AtomicReference<String> responseType;

    /** A content coding to declare, when a case needs one. */
    private AtomicReference<String> responseEncoding;

    @BeforeEach
    void startUpstream() throws IOException {
        responseBody = new AtomicReference<>("{\"ok\":true}");
        rawResponse = new AtomicReference<>(null);
        responseType = new AtomicReference<>("application/json");
        responseEncoding = new AtomicReference<>(null);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            final Map<String, String> headers = new java.util.LinkedHashMap<>();
            exchange.getRequestHeaders()
                    .forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            headers.put(":target", exchange.getRequestURI().toString());
            headers.put(":method", exchange.getRequestMethod());
            received.add(headers);
            final byte[] arrived = exchange.getRequestBody().readAllBytes();
            rawBody.set(arrived);
            body.set(new String(arrived, StandardCharsets.UTF_8));

            final byte[] raw = rawResponse.get();
            final byte[] payload = raw != null
                    ? raw : responseBody.get().getBytes(StandardCharsets.UTF_8);
            if (responseType.get() != null) {
                exchange.getResponseHeaders().set("Content-Type", responseType.get());
            }
            if (responseEncoding.get() != null) {
                exchange.getResponseHeaders().set("Content-Encoding", responseEncoding.get());
            }
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

    private VaultProxy proxy(Path socket, String header, String prefix, String credential) {
        return proxy(socket, header, prefix, presented -> {
            if (!PHANTOM.equals(presented)) {
                return new TokenExchange.Rejected();
            }
            return credential.isBlank()
                    ? new TokenExchange.Unavailable("no credential for this task")
                    : new TokenExchange.Granted(credential);
        });
    }

    @Test
    void anExpiredTokenSaysSoRatherThanLookingLikeAWrongCredential(@TempDir Path dir)
            throws IOException {

        // The agent's own report is identical either way, so the difference has to be in the
        // message: the token was this task's, the task simply outlived it.
        final java.time.Instant when = java.time.Instant.parse("2026-09-04T18:00:00Z");
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "",
                presented -> new TokenExchange.Expired(when))) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\ncontent-length: 0\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 401");
            assertThat(response)
                    .contains("expired at " + when)
                    .contains("resume the task")
                    .contains("--token-hours");
            assertThat(response).as("it is not the same case as a token from another task")
                    .doesNotContain("not this task's token");
            assertThat(received).as("nothing left the machine").isEmpty();
        }
    }

    private VaultProxy proxy(Path socket, String header, String prefix, TokenExchange exchange) {
        final VaultProxy proxy = new VaultProxy(socket,
                "http://127.0.0.1:" + upstream.getAddress().getPort(), exchange, header, prefix);
        Thread.ofPlatform().daemon().start(proxy);
        return proxy;
    }

    /** Sends one raw request over the socket and returns the whole response. */
    @Test
    void aCompressedRequestBodyReachesTheUpstreamByteForByteWithItsEncoding(@TempDir Path dir) throws IOException {

        // Measured on 2026-09-30 after a finding elsewhere: an agent that compresses its requests (Content-Encoding:
        // zstd) failed at a broker that decoded bodies. This one only swaps the credential header, so the body goes
        // on as the same bytes, its coding named as it was.
        final Path socket = dir.resolve("vault.sock");
        // A real frame now: the broker reads a zstd body for a token grant, and refuses one that does not decode.
        final byte[] plain = "{\"input\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        final io.airlift.compress.v3.zstd.ZstdCompressor zstd = io.airlift.compress.v3.zstd.ZstdCompressor.create();
        final byte[] frame = new byte[zstd.maxCompressedLength(plain.length)];
        final byte[] compressed = java.util.Arrays.copyOf(frame, zstd.compress(plain, 0, plain.length, frame, 0,
                frame.length));
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL);
                SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.write(java.nio.ByteBuffer.wrap(("POST /v1/responses HTTP/1.1\r\n" + "x-api-key: " + PHANTOM
                    + "\r\ncontent-encoding: zstd\r\ncontent-length: " + compressed.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8)));
            channel.write(java.nio.ByteBuffer.wrap(compressed));
            final String response = new String(java.nio.channels.Channels.newInputStream(channel).readAllBytes(),
                    StandardCharsets.UTF_8);

            assertThat(response).startsWith("HTTP/1.1 200");
            assertThat(received.get(0).get("content-encoding")).isEqualTo("zstd");
            assertThat(rawBody.get()).containsExactly(compressed);
            assertThat(received.get(0).get("x-api-key")).isEqualTo(REAL);
        }
    }

    private String send(Path socket, String request) throws IOException {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.write(java.nio.ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final InputStream in = java.nio.channels.Channels.newInputStream(channel);
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void withholdsAnAnswerThatCarriesACredential(@TempDir Path dir) throws IOException {

        // Renewal is the one exchange whose answer is itself a credential. Measured before this
        // existed: the container received access_token and refresh_token verbatim, which is the
        // single thing the phantom token exists to prevent.
        final Path socket = dir.resolve("vault.sock");
        responseBody.set("{\"access_token\":\"sk-live-RENEWED\",\"refresh_token\":\"rt-8b2\"}");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "GET /v1/whatever HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 403");
            assertThat(response).contains("answered with a credential");
            assertThat(response).doesNotContain("sk-live-RENEWED").doesNotContain("rt-8b2");
        }
    }

    @Test
    void anAnswerThatMerelyTalksAboutTokensIsPassedOn(@TempDir Path dir) throws IOException {

        // The false positive worth avoiding: an agent asking a model about OAuth gets those very
        // words back. Inside a JSON string the quotes arrive escaped, which is what tells the two
        // apart - a completion is not a credential.
        final Path socket = dir.resolve("vault.sock");
        responseBody.set("{\"content\":[{\"text\":\"Send {\\\"refresh_token\\\": ...} to renew.\"}]}");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "GET /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 200");
            assertThat(response).contains("to renew.");
        }
    }

    @Test
    void refusesARenewalBeforeItReachesTheProvider(@TempDir Path dir) throws IOException {

        // Refused on the way out, not on the way back: forwarding it would attach the real
        // credential to a request whose answer is a new one, and the provider may rotate what
        // Sokar holds as a side effect.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/oauth/token HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n"
                    + "content-length: 30\r\n\r\n{\"grant_type\":\"refresh_token\"}");

            assertThat(response).startsWith("HTTP/1.1 403");
            assertThat(response).contains("cannot be renewed or exchanged");
            assertThat(received).isEmpty();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "grant_type=refresh%5Ftoken",
        "grant%5Ftype=refresh_token",
        "{\"grant_type\":\"refresh\\u005ftoken\"}",
        "{\"grant\\u005ftype\": \"client_credentials\"}"})
    void refusesARenewalHoweverItsBodyIsSpelled(final String body, @TempDir Path dir) throws IOException {

        // The pattern read the raw bytes, so an encoding of the same words - a form's %5F, a JSON escape - went out
        // with the real credential attached.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/oauth/token HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n"
                    + "content-length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body);

            assertThat(response).startsWith("HTTP/1.1 403");
            assertThat(received).isEmpty();
        }
    }

    @Test
    void refusesARenewalInAGzippedBody(@TempDir Path dir) throws IOException {

        final java.io.ByteArrayOutputStream zipped = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(zipped)) {
            gzip.write("grant_type=refresh_token".getBytes(StandardCharsets.UTF_8));
        }
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL);
                java.nio.channels.SocketChannel channel = java.nio.channels.SocketChannel.open(
                        java.net.UnixDomainSocketAddress.of(socket))) {
            final byte[] head = ("POST /v1/oauth/token HTTP/1.1\r\nx-api-key: " + PHANTOM
                    + "\r\ncontent-encoding: gzip\r\ncontent-length: " + zipped.size() + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            channel.write(java.nio.ByteBuffer.wrap(head));
            channel.write(java.nio.ByteBuffer.wrap(zipped.toByteArray()));
            final String response = new String(java.nio.channels.Channels.newInputStream(channel).readAllBytes(),
                    StandardCharsets.UTF_8);

            assertThat(response).startsWith("HTTP/1.1 403");
            assertThat(received).isEmpty();
        }
    }

    @Test
    void refusesAClientCredentialsGrantForTheSameReason(@TempDir Path dir) throws IOException {

        // The refusal began as refresh_token alone, and the reason written beside it - forwarding
        // attaches the real credential to a request whose answer is a new one - is exactly as
        // true of client_credentials. Reported as latent: no supported provider mints this way,
        // so nothing reached it, and it was one pattern away from being reachable.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /oauth2/token HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n"
                    + "content-type: application/x-www-form-urlencoded\r\n"
                    + "content-length: 29\r\n\r\ngrant_type=client_credentials");

            assertThat(response).startsWith("HTTP/1.1 403");
            assertThat(received).as("the real credential never left this machine").isEmpty();
        }
    }

    @Test
    void putsTheRealCredentialInTheDeclaredHeader(@TempDir Path dir) throws IOException {

        // Given
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            // When
            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n"
                    + "content-length: 2\r\n\r\n{}");

            // Then
            assertThat(response).startsWith("HTTP/1.1 200");
            assertThat(received).hasSize(1);
            assertThat(received.get(0).get("x-api-key")).isEqualTo(REAL);
            assertThat(received.get(0).get(":target")).isEqualTo("/v1/messages");
            assertThat(body.get()).isEqualTo("{}");
        }
    }

    @Test
    void asksTheProviderForAnAnswerTheScanCanRead(@TempDir Path dir) throws IOException {

        // Reported from the VM: Claude Code sends 'accept-encoding: gzip, deflate, br', the
        // provider answered every request compressed, and the proxy withheld each answer as one it
        // could not read - so the agent saw an API error on a request that had succeeded.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n"
                    + "accept-encoding: gzip, deflate, br\r\n"
                    + "content-length: 2\r\n\r\n{}");

            assertThat(response).startsWith("HTTP/1.1 200");
            assertThat(received.get(0).get("accept-encoding")).isEqualTo("identity");
        }
    }

    @Test
    void usesABearerPrefixWhenTheRouteAsksForOne(@TempDir Path dir) throws IOException {

        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "Authorization", "Bearer ", REAL)) {

            send(socket, "POST /v1/messages HTTP/1.1\r\nauthorization: Bearer " + PHANTOM
                    + "\r\ncontent-length: 0\r\n\r\n");

            assertThat(received.get(0).get("authorization")).isEqualTo("Bearer " + REAL);
        }
    }

    @Test
    void theProviderNeverSeesThePhantomToken(@TempDir Path dir) throws IOException {

        // The point of the whole scheme. An agent may also send its token in a header the route
        // does not name, so every credential header is stripped, not just the expected one.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n"
                    + "authorization: Bearer " + PHANTOM + "\r\n"
                    + "private-token: " + PHANTOM + "\r\n"
                    + "content-length: 0\r\n\r\n");

            assertThat(received.get(0).values()).noneMatch(value -> value.contains("sokar_pt_"));
            assertThat(received.get(0)).doesNotContainKey("private-token");
            assertThat(received.get(0)).doesNotContainKey("authorization");
        }
    }

    @Test
    void rejectsATokenThatIsNotThisTasks(@TempDir Path dir) throws IOException {

        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: sokar_pt_someOtherTask\r\ncontent-length: 0\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 401");
            assertThat(response).contains("authentication_error");
            // And crucially the request never left the machine.
            assertThat(received).isEmpty();
        }
    }

    @Test
    void rejectsARequestWithNoCredentialAtAll(@TempDir Path dir) throws IOException {

        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            assertThat(send(socket, "GET /v1/models HTTP/1.1\r\n\r\n")).startsWith("HTTP/1.1 401");
            assertThat(received).isEmpty();
        }
    }

    @Test
    void saysSoWhenTheVaultHasNothing(@TempDir Path dir) throws IOException {

        // Distinguishable from a rejected token, because the operator's fix is different: unlock
        // the vault or store a credential, rather than restart the task.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", "")) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\ncontent-length: 0\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 503");
            assertThat(response).contains("no credential for this task");
            assertThat(received).isEmpty();
        }
    }

    @Test
    void forwardsAChunkedRequestBody(@TempDir Path dir) throws IOException {

        // Real clients do this, and a body that arrives in chunks must reach the provider whole.
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n"
                    + "transfer-encoding: chunked\r\n\r\n"
                    + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n");

            assertThat(body.get()).isEqualTo("hello world");
        }
    }

    @Test
    void streamsTheAnswerBackRatherThanBufferingIt(@TempDir Path dir) throws IOException {

        // A completion arrives as server-sent events. The body must come back chunked, or the
        // agent shows nothing until the answer is complete.
        responseBody.set("data: {\"delta\":\"one\"}\n\ndata: {\"delta\":\"two\"}\n\n");
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\ncontent-length: 0\r\n\r\n");

            assertThat(response).contains("Transfer-Encoding: chunked");
            assertThat(response).contains("data: {\"delta\":\"one\"}");
            assertThat(response).contains("data: {\"delta\":\"two\"}");
            assertThat(response).endsWith("0\r\n\r\n");
        }
    }

    @Test
    void anMcpServersFirstEventReachesTheAgentBeforeTheServerSaysMore(@TempDir Path dir) throws Exception {

        // An MCP server over HTTP answers in server-sent events and may send one small event and then wait - a
        // notification stream, a progress event. The answer was held until 8 KiB had arrived, so the agent saw nothing
        // while the server waited. Its session header has to cross both ways as well.
        upstream.createContext("/mcp", exchange -> {
            received.add(java.util.Map.of("mcp-session-id",
                    String.valueOf(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Mcp-Session-Id", "s-1");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("event: message\ndata: {\"first\":true}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(3000);
                out.write("event: message\ndata: {\"second\":true}\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL);
                SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.write(java.nio.ByteBuffer.wrap(("POST /mcp HTTP/1.1\r\nx-api-key: " + PHANTOM
                    + "\r\nMcp-Session-Id: s-1\r\nAccept: application/json, text/event-stream\r\n"
                    + "content-length: 2\r\n\r\n{}").getBytes(StandardCharsets.UTF_8)));
            final long started = System.nanoTime();
            final InputStream in = java.nio.channels.Channels.newInputStream(channel);
            final StringBuilder seen = new StringBuilder();
            final byte[] buffer = new byte[1024];
            while (!seen.toString().contains("\"first\"")) {
                final int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                seen.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            final long millis = (System.nanoTime() - started) / 1_000_000;

            assertThat(seen.toString()).contains("{\"first\":true}").containsIgnoringCase("Mcp-Session-Id: s-1");
            assertThat(millis).as("the first event, before the server's pause ends").isLessThan(2000);
            assertThat(received).anySatisfy(headers -> assertThat(headers).containsEntry("mcp-session-id", "s-1"));
        }
    }

    @Test
    void theDirectoryIsWhatKeepsOtherUsersOut(@TempDir Path dir) throws IOException {

        // Measured: a rootless container's agent user is a subordinate uid on the host and
        // cannot open an owner-only socket - the connect fails. So the socket is permissive and
        // the directory is not. Asserting both, because either alone is wrong: an owner-only
        // socket breaks every task, and a traversable directory exposes the proxy to other
        // host users.
        final Path socket = dir.resolve("nested").resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)))
                    .isEqualTo("rw-rw-rw-");
            assertThat(PosixFilePermissions
                    .toString(Files.getPosixFilePermissions(socket.getParent())))
                    .isEqualTo("rwx------");
        }
    }

    @Test
    void removesTheSocketWhenItStops(@TempDir Path dir) throws IOException {

        // A socket file left behind makes the next run's bind fail, or worse, look like it worked.
        final Path socket = dir.resolve("vault.sock");
        proxy(socket, "x-api-key", "", REAL).close();
        assertThat(Files.exists(socket)).isFalse();
    }

    @Test
    void keepsServingAfterARequestItRejected(@TempDir Path dir) throws IOException {

        final Path socket = dir.resolve("vault.sock");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            send(socket, "POST /v1 HTTP/1.1\r\nx-api-key: wrong\r\ncontent-length: 0\r\n\r\n");
            final String second = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\ncontent-length: 0\r\n\r\n");

            assertThat(second).startsWith("HTTP/1.1 200");
        }
    }

    /**
     * Harmless answer bytes, exactly {@code length} of them, carrying no watched member name.
     *
     * @param length How many bytes.
     * @return The filler.
     */
    private static String filler(int length) {
        final StringBuilder text = new StringBuilder("{\"content\":\"");
        while (text.length() < length) {
            text.append("the answer continues and says nothing in particular. ");
        }
        return text.substring(0, length);
    }

    /**
     * Asserts that a credential in a provider's answer did not reach the container.
     *
     * @param response Everything the container received.
     * @param secret The credential the provider sent.
     */
    private static void assertNeverArrived(String response, String secret) {
        assertThat(response).as("the credential did not cross").doesNotContain(secret);
        assertThat(response).as("an answer that cannot be classified safely is terminated,"
                + " so the agent sees a truncated stream rather than a complete one")
                .doesNotEndWith("0\r\n\r\n");
    }

    @Test
    void withholdsACredentialFieldSpelledWithEscapes(@TempDir Path dir) throws IOException {

        // "\u0061ccess_token" is the same member as "access_token" to every JSON parser and was
        // not the same string to a literal match. Asserted here rather than only on the scanner,
        // because what matters is what leaves the proxy.
        final Path socket = dir.resolve("vault.sock");
        // Every watched name in this answer is escaped. An earlier version of this test also
        // carried a plain "refresh_token", which made it pass against a literal matcher too -
        // it was asserting the scan worked while testing nothing about escapes.
        responseBody.set("{\"\\u0061ccess_token\":\"sk-live-ESCAPED\","
                + "\"\\u0072efresh_token\":\"rt-esc\"}");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "GET /v1/whatever HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 403");
            assertThat(response).doesNotContain("sk-live-ESCAPED").doesNotContain("rt-esc");
        }
    }

    @Test
    void cutsOffACredentialThatArrivesAfterTheStreamedPrefix(@TempDir Path dir)
            throws IOException {

        // The earlier shape scanned a bounded prefix and streamed the rest unread, and a
        // credential past that point reached the container. The headers are already gone by
        // then, so there is no status left to answer with: the only thing that still withholds
        // it is to stop writing.
        final Path socket = dir.resolve("vault.sock");
        responseBody.set(filler(VaultProxy.PEEK * 3)
                + "\",\"access_token\":\"sk-live-LATE\"}");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\ncontent-length: 0\r\n\r\n");

            assertThat(response).as("the harmless prefix was already streaming")
                    .contains("Transfer-Encoding: chunked");
            assertNeverArrived(response, "sk-live-LATE");
        }
    }

    @Test
    void cutsOffACredentialWhoseNameStraddlesTwoReads(@TempDir Path dir) throws IOException {

        // Deterministic rather than timing-dependent: the first read is exactly PEEK bytes, so
        // placing the member name to end four bytes past it splits "access_token" across the two
        // scans whatever the network does. This is the case the carried overlap exists for, and
        // without it each half matches nothing.
        final String name = "\"access_token\":";
        final Path socket = dir.resolve("vault.sock");
        responseBody.set(filler(VaultProxy.PEEK - name.length() + 4)
                + name + "\"sk-live-SPLIT\"}");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\ncontent-length: 0\r\n\r\n");

            assertNeverArrived(response, "sk-live-SPLIT");
        }
    }

    @Test
    void cutsOffACredentialThatFollowsMalformedUtf8(@TempDir Path dir) throws IOException {

        // A provider that answers with bytes that are not valid UTF-8 must not be a way past the
        // scan. Decoding replaces them rather than throwing, so the ASCII member name after them
        // is still a member name - asserted rather than assumed, because "it cannot happen" was
        // the reasoning that produced the bounded prefix.
        final Path socket = dir.resolve("vault.sock");
        final byte[] head = filler(VaultProxy.PEEK * 2).getBytes(StandardCharsets.UTF_8);
        final byte[] malformed = {(byte) 0xC3, (byte) 0x28, (byte) 0xFF, (byte) 0xFE,
                (byte) 0xE2, (byte) 0x82};
        final byte[] tail = ",\"access_token\":\"sk-live-AFTERBADBYTES\"}"
                .getBytes(StandardCharsets.UTF_8);
        final byte[] answer = new byte[head.length + malformed.length + tail.length];
        System.arraycopy(head, 0, answer, 0, head.length);
        System.arraycopy(malformed, 0, answer, head.length, malformed.length);
        System.arraycopy(tail, 0, answer, head.length + malformed.length, tail.length);
        rawResponse.set(answer);
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\ncontent-length: 0\r\n\r\n");

            assertNeverArrived(response, "sk-live-AFTERBADBYTES");
        }
    }

    @Test
    void cutsOffACredentialSeparatedFromItsColonByMoreWhitespaceThanIsCarried(@TempDir Path dir)
            throws IOException {

        // JSON allows any amount of whitespace between a member name and its colon, and the
        // carried tail is finite. Enough of it and the name is out of the window before the colon
        // arrives, so neither half ever matches - valid framing, and a provider can produce it
        // deliberately. The run is longer than one read, so at least one whole read is nothing
        // but whitespace whatever the network does.
        final String name = "\"access_token\"";
        final Path socket = dir.resolve("vault.sock");
        responseBody.set(filler(VaultProxy.PEEK - name.length()) + name
                + " ".repeat(VaultProxy.PEEK * 3) + ":\"sk-live-PATIENT\"}");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\ncontent-length: 0\r\n\r\n");

            assertNeverArrived(response, "sk-live-PATIENT");
        }
    }

    @Test
    void refusesAnAnswerItCannotReadBecauseItIsCompressed(@TempDir Path dir) throws IOException {

        // A gzipped body reaches the scan as compressed bytes: a member name is not absent from
        // it, it is not *present* to be found. Streaming what cannot be judged is the same as
        // judging it harmless, so this fails closed - and says which of the two reasons it was.
        final Path socket = dir.resolve("vault.sock");
        responseEncoding.set("gzip");
        responseBody.set("{\"access_token\":\"sk-live-HIDDEN\"}");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "GET /v1/whatever HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 502");
            assertThat(response).contains("content-encoding gzip");
            assertThat(response).doesNotContain("sk-live-HIDDEN");
        }
    }

    @Test
    void refusesAMediaTypeTheScanDoesNotUnderstand(@TempDir Path dir) throws IOException {

        // The scan looks for JSON member names. A body in some other framing may carry a
        // credential in a shape it does not look for at all, so the type it was told decides
        // whether it is allowed to judge at all.
        final Path socket = dir.resolve("vault.sock");
        responseType.set("application/octet-stream");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "GET /v1/whatever HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 502");
            assertThat(response).contains("content-type application/octet-stream");
        }
    }

    @Test
    void readsAStreamOfEventsAndAnswersWithCharsetsDeclared(@TempDir Path dir) throws IOException {

        // The two types a provider actually answers with, one of them carrying a charset. A media
        // type is matched without its parameters, or every provider that declares one would be
        // refused for saying more rather than less.
        final Path socket = dir.resolve("vault.sock");
        responseType.set("text/event-stream; charset=utf-8");
        responseBody.set("data: {\"delta\":\"one\"}\n\n");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            assertThat(send(socket, "POST /v1/messages HTTP/1.1\r\n" + "x-api-key: " + PHANTOM
                    + "\r\ncontent-length: 0\r\n\r\n")).startsWith("HTTP/1.1 200");
        }
        responseType.set("application/json; charset=utf-8");
        final Path second = dir.resolve("vault2.sock");
        try (VaultProxy proxy = proxy(second, "x-api-key", "", REAL)) {

            assertThat(send(second, "POST /v1/messages HTTP/1.1\r\n" + "x-api-key: " + PHANTOM
                    + "\r\ncontent-length: 0\r\n\r\n")).startsWith("HTTP/1.1 200");
        }
    }

    @Test
    void anAnswerWithNoBodyIsNotRefusedForHavingNoType(@TempDir Path dir) throws IOException {

        // Nothing in an empty answer can carry a credential, and refusing one would break every
        // 204 and every redirect - a fail-closed rule that breaks ordinary traffic gets switched
        // off by whoever it annoys.
        final Path socket = dir.resolve("vault.sock");
        responseType.set(null);
        responseBody.set("");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            assertThat(send(socket, "GET /v1/whatever HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n\r\n")).startsWith("HTTP/1.1 200");
        }
    }

    @Test
    void refusesABodyThatDeclaresNoTypeAtAll(@TempDir Path dir) throws IOException {

        // The other half of the case above: bytes with no declared type are bytes the scan has
        // been told nothing about.
        final Path socket = dir.resolve("vault.sock");
        responseType.set(null);
        responseBody.set("{\"access_token\":\"sk-live-UNDECLARED\"}");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "GET /v1/whatever HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 502");
            assertThat(response).contains("no content-type");
            assertThat(response).doesNotContain("sk-live-UNDECLARED");
        }
    }

    @Test
    void theRealCredentialItselfNeverReachesTheContainer(@TempDir Path dir) throws IOException {

        // The other direction of the same boundary: whatever the provider answers, the credential
        // the proxy attached on the way out is not in what comes back.
        final Path socket = dir.resolve("vault.sock");
        responseBody.set("{\"ok\":true}");
        try (VaultProxy proxy = proxy(socket, "x-api-key", "", REAL)) {

            final String response = send(socket, "POST /v1/messages HTTP/1.1\r\n"
                    + "x-api-key: " + PHANTOM + "\r\ncontent-length: 0\r\n\r\n");

            assertThat(response).startsWith("HTTP/1.1 200").doesNotContain(REAL);
            assertThat(received.get(0)).containsEntry("x-api-key", REAL);
        }
    }
}
