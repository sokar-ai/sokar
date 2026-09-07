package org.fuin.sokar.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the varlink client and server, talking to each other.
 * <p>
 * Conformance against an independent implementation is checked separately by
 * {@link VarlinkInteropTest}, which drives systemd's {@code varlinkctl}. Two halves written by the
 * same person agreeing with each other proves only that they are consistent.
 */
class VarlinkTest {

    private static final String INTERFACE = "org.fuin.sokar.Clearance1";

    private VarlinkServer server(Path dir) {
        final VarlinkServer server = new VarlinkServer(dir.resolve("v.sock"), INTERFACE);
        Thread.ofVirtual().start(server);
        return server;
    }

    @Test
    void callsAMethodAndGetsAReply(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method("Ping", (parameters, replies) -> replies.last(Map.of("pong", Boolean.TRUE)));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(client.call(INTERFACE + ".Ping", Map.of())).containsEntry("pong", Boolean.TRUE);
            }
        }
    }

    @Test
    void passesParametersThrough(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method("Echo", (parameters, replies) -> replies.last(Map.of("got", parameters.get("say"))));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThat(client.call(INTERFACE + ".Echo", Map.of("say", "hello")))
                        .containsEntry("got", "hello");
            }
        }
    }

    @Test
    void streamsWithMore(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method("Stream", (parameters, replies) -> {
                for (int i = 1; i <= 3; i++) {
                    replies.more(Map.of("seq", Integer.valueOf(i)));
                }
                replies.last(Map.of("seq", Integer.valueOf(4)));
            });

            final List<Object> seen = new ArrayList<>();
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                client.callMore(INTERFACE + ".Stream", Map.of(), reply -> {
                    seen.add(reply.get("seq"));
                    return true;
                });
            }
            assertThat(seen).hasSize(4);
        }
    }

    @Test
    void stopsWhenTheConsumerAsksTo(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method("Stream", (parameters, replies) -> {
                for (int i = 0; i < 100; i++) {
                    replies.more(Map.of("seq", Integer.valueOf(i)));
                }
                replies.last(Map.of("seq", Integer.valueOf(100)));
            });

            final AtomicInteger seen = new AtomicInteger();
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                client.callMore(INTERFACE + ".Stream", Map.of(), reply -> seen.incrementAndGet() < 3);
            }
            assertThat(seen).hasValue(3);
        }
    }

    @Test
    void endsTheStreamWhenContinuesIsAbsent(@TempDir Path dir) throws IOException {

        // The last reply omits 'continues'. Treating its absence as "keep waiting" is how a client
        // hangs at the end of a perfectly good stream.
        try (VarlinkServer server = server(dir)) {
            server.method("One", (parameters, replies) -> replies.last(Map.of("done", Boolean.TRUE)));

            final AtomicInteger seen = new AtomicInteger();
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                client.callMore(INTERFACE + ".One", Map.of(), reply -> {
                    seen.incrementAndGet();
                    return true;
                });
            }
            assertThat(seen).hasValue(1);
        }
    }

    @Test
    void aStreamCutShortIsAnErrorRatherThanAnEnding(@TempDir Path dir) throws Exception {

        // Measured against the real daemon: killing it mid-stream closes the socket with no final
        // reply, and returning normally there is indistinguishable from a stream that finished. A
        // client would stop watching and go on showing what it last saw - which for a fleet view,
        // or a clearance prompt that expires, is worse than an error.
        //
        // Written against a bare socket rather than VarlinkServer, because what is being modeled
        // is a service that dies: one reply, then the connection ends mid-stream. Closing the
        // server object would not do it - the accepted connection stays open and the client waits
        // for ever, which is how the first version of this test hung.
        final Path socket = dir.resolve("dying.sock");
        try (java.nio.channels.ServerSocketChannel channel = java.nio.channels.ServerSocketChannel
                .open(java.net.StandardProtocolFamily.UNIX)) {
            channel.bind(java.net.UnixDomainSocketAddress.of(socket));
            final Thread service = Thread.ofVirtual().start(() -> {
                try (java.nio.channels.SocketChannel client = channel.accept()) {
                    final java.nio.ByteBuffer in = java.nio.ByteBuffer.allocate(4096);
                    client.read(in);
                    client.write(java.nio.ByteBuffer.wrap(
                            ("{\"parameters\":{\"n\":1},\"continues\":true}\0")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                } catch (IOException ex) {
                    // The client went first, which this test does not care about.
                }
            });

            try (VarlinkClient client = new VarlinkClient(socket)) {
                final List<Map<String, Object>> seen = new ArrayList<>();
                assertThatThrownBy(() -> client.callMore(INTERFACE + ".Endless", Map.of(),
                        reply -> {
                            seen.add(reply);
                            return true;
                        }))
                        .isInstanceOf(VarlinkException.class)
                        .hasMessageContaining("before it ended");
                assertThat(seen).as("what did arrive is still delivered").hasSize(1);
            }
            service.join(java.time.Duration.ofSeconds(5));
        }
    }

    @Test
    void reportsAnUnknownMethodAsAVarlinkError(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                assertThatThrownBy(() -> client.call(INTERFACE + ".Nope", Map.of()))
                        .isInstanceOf(VarlinkException.class)
                        .hasMessageContaining("MethodNotFound");
            }
        }
    }

    @Test
    void turnsAMethodFailureIntoAnErrorReplyRatherThanDroppingTheConnection(@TempDir Path dir)
            throws IOException {

        try (VarlinkServer server = server(dir)) {
            server.method("Boom", (parameters, replies) -> {
                throw new IllegalStateException("deliberate");
            });
            server.method("Ping", (parameters, replies) -> replies.last(Map.of("pong", Boolean.TRUE)));

            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThatThrownBy(() -> client.call(INTERFACE + ".Boom", Map.of()))
                        .isInstanceOf(VarlinkException.class)
                        .hasMessageContaining("deliberate");

                // The connection must survive, or one bad call costs every later one.
                assertThat(client.call(INTERFACE + ".Ping", Map.of())).containsEntry("pong", Boolean.TRUE);
            }
        }
    }

    @Test
    void answersGetInfoWithoutBeingTold(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                final Map<String, Object> info = client.call("org.varlink.service.GetInfo", Map.of());

                assertThat(info).containsEntry("product", "Sokar");
                assertThat(((List<?>) info.get("interfaces")).contains(INTERFACE)).isTrue();
            }
        }
    }

    @Test
    void createsTheSocketWithOwnerOnlyPermissions(@TempDir Path dir) throws IOException {

        try (VarlinkServer server = server(dir)) {
            assertThat(java.nio.file.Files.getPosixFilePermissions(server.socketPath()))
                    .containsExactlyInAnyOrder(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        }
    }

    @Test
    void removesTheSocketWhenItStops(@TempDir Path dir) {

        final Path socket;
        try (VarlinkServer server = server(dir)) {
            socket = server.socketPath();
            assertThat(socket).exists();
        }
        assertThat(socket).doesNotExist();
    }
}
