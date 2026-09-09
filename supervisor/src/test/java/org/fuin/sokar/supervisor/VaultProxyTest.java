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

    private AtomicReference<String> responseBody;

    @BeforeEach
    void startUpstream() throws IOException {
        responseBody = new AtomicReference<>("{\"ok\":true}");
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            final Map<String, String> headers = new java.util.LinkedHashMap<>();
            exchange.getRequestHeaders()
                    .forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            headers.put(":target", exchange.getRequestURI().toString());
            headers.put(":method", exchange.getRequestMethod());
            received.add(headers);
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            final byte[] payload = responseBody.get().getBytes(StandardCharsets.UTF_8);
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
}
