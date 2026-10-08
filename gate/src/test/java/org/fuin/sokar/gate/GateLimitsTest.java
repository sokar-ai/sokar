package org.fuin.sokar.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateLimitsTest {

    @TempDir
    Path dir;

    private final TaskToken token = TaskToken.mint();

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpRequest.Builder request(final GitHttpServer server, final String path) {
        return HttpRequest.newBuilder(URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.port() + "/mirror.git" + path)).header("Authorization", "Basic "
                        + java.util.Base64.getEncoder().encodeToString(("sokar:" + token.value())
                                .getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30));
    }

    @Test
    void aGateServesAFewRequestsAtOnceAndAnswersTheNextAtOnceWith503() throws Exception {
        // Every request started a git on the host with no limit: an agent holding its gate's token could take the
        // machine's processes and memory from every other task.
        final CountDownLatch entered = new CountDownLatch(GitHttpServer.AT_ONCE);
        final CountDownLatch release = new CountDownLatch(1);
        final GitHttpServer.GitProcess blocking = (arguments, input) -> {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new byte[0];
        };
        try (GitHttpServer server = new GitHttpServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                dir.resolve("mirror.git"), token, blocking)) {
            server.start();
            final List<CompletableFuture<HttpResponse<Void>>> held = new ArrayList<>();
            for (int i = 0; i < GitHttpServer.AT_ONCE; i++) {
                held.add(client.sendAsync(request(server, "/info/refs?service=git-upload-pack").GET().build(),
                        HttpResponse.BodyHandlers.discarding()));
            }
            assertThat(entered.await(20, TimeUnit.SECONDS)).as("the first requests are served").isTrue();

            final HttpResponse<Void> next = client.send(request(server, "/info/refs?service=git-upload-pack").GET()
                    .build(), HttpResponse.BodyHandlers.discarding());

            assertThat(next.statusCode()).isEqualTo(503);
            assertThat(next.headers().firstValue("Retry-After")).contains("1");
            release.countDown();
            for (final CompletableFuture<HttpResponse<Void>> each : held) {
                assertThat(each.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            }
            assertThat(client.send(request(server, "/info/refs?service=git-upload-pack").GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode()).as("once they are done").isEqualTo(200);
        }
    }

    @Test
    void aPushAndAFetchReachGitFromAFileNeverAsBytesHeldWhole() throws Exception {
        // A request was read into memory whole, up to 1 GiB, and handed to git as an array.
        final AtomicReference<String> spooled = new AtomicReference<>();
        final GitHttpServer.GitProcess git = new GitHttpServer.GitProcess() {
            @Override
            public byte[] run(final List<String> arguments, final byte[] input) {
                throw new AssertionError("a request handed to git as bytes: " + arguments);
            }

            @Override
            public void stream(final List<String> arguments, final Path input, final OutputStream output)
                    throws IOException {
                assertThat(input).as("spooled beside the mirror").hasParent(dir);
                spooled.set(Files.readString(input, StandardCharsets.ISO_8859_1));
                output.write("0000".getBytes(StandardCharsets.US_ASCII));
            }
        };
        final String push = pkt("0".repeat(40) + " " + "a".repeat(40) + " refs/sokar/incoming/t\0report-status\n")
                + "0000PACK the pack";
        try (GitHttpServer server = new GitHttpServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                dir.resolve("mirror.git"), token, git)) {
            server.start();

            assertThat(client.send(request(server, "/git-receive-pack").POST(HttpRequest.BodyPublishers.ofString(push))
                    .build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            assertThat(spooled.get()).isEqualTo(push);

            assertThat(client.send(request(server, "/git-upload-pack").POST(HttpRequest.BodyPublishers.ofString(
                    "0032want " + "b".repeat(40) + "\n00000009done\n")).build(),
                    HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("0000");
            assertThat(spooled.get()).startsWith("0032want");
        }
        try (var left = Files.list(dir)) {
            assertThat(left.map(Path::getFileName).map(Path::toString).toList()).as("no spooled request is kept")
                    .noneMatch(name -> name.startsWith("request-"));
        }
    }

    @Test
    void aCompressedRequestThatInflatesBeyondTheLimitIsRefusedWhileItIsWritten() throws IOException {
        final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            final byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i <= GitHttpServer.INFLATED_LIMIT / zeros.length; i++) {
                gzip.write(zeros);
            }
        }
        final Path into = dir.resolve("request");

        assertThatThrownBy(() -> GitHttpServer.spool(new ByteArrayInputStream(compressed.toByteArray()), "gzip", into))
                .hasMessageContaining("inflating beyond");
        assertThat(Files.size(into)).as("what was written stops at the limit").isLessThanOrEqualTo(
                GitHttpServer.INFLATED_LIMIT);
        assertThatThrownBy(() -> GitHttpServer.spool(new ByteArrayInputStream(new byte[0]), "br", into))
                .isInstanceOf(GateException.class);
    }

    private static String pkt(final String payload) {
        return String.format("%04x", payload.getBytes(StandardCharsets.UTF_8).length + 4) + payload;
    }
}
