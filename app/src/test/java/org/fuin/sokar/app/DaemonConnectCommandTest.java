package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.Json;
import org.fuin.sokar.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link DaemonConnectCommand}, driven against a real varlink server on a real socket.
 * <p>
 * What is being checked is that bytes cross unchanged in both directions, which is the entire job:
 * a bridge that understood the protocol would be a second implementation of it in the least useful
 * place.
 */
class DaemonConnectCommandTest {

    private final StringWriter err = new StringWriter();

    private static VarlinkServer answering(Path socket) {
        final VarlinkServer server = new VarlinkServer(socket, "org.example.Test");
        server.method("Echo", (parameters, replies) ->
                replies.last(Map.of("said", String.valueOf(parameters.get("say")))));
        return server;
    }

    private static String call(String method, Map<String, Object> parameters) {
        return Json.write(Map.of("method", method, "parameters", parameters)) + "\0";
    }

    @Test
    void carriesACallToTheDaemonAndTheReplyBack(@TempDir Path dir) throws Exception {

        final Path socket = dir.resolve("sokard.sock");
        try (VarlinkServer server = answering(socket)) {
            Thread.ofVirtual().start(server);
            waitFor(socket);

            final ByteArrayOutputStream replies = new ByteArrayOutputStream();
            DaemonConnectCommand.bridge(socket,
                    new ByteArrayInputStream(call("org.example.Test.Echo", Map.of("say", "hello"))
                            .getBytes(StandardCharsets.UTF_8)),
                    replies);

            assertThat(replies.toString(StandardCharsets.UTF_8)).contains("\"said\":\"hello\"");
        }
    }

    @Test
    void carriesEveryReplyOfAStreamAsItArrives(@TempDir Path dir) throws Exception {

        // The case a line-buffered or block-buffered bridge would break: a stream's replies arrive
        // over time, and one held back is indistinguishable from a daemon that stopped answering.
        final Path socket = dir.resolve("sokard.sock");
        final VarlinkServer server = new VarlinkServer(socket, "org.example.Test");
        server.method("Count", (parameters, replies) -> {
            for (int i = 0; i < 3; i++) {
                replies.more(Map.of("n", i));
            }
            replies.last(Map.of("n", 3));
        });
        try (server) {
            Thread.ofVirtual().start(server);
            waitFor(socket);

            final ByteArrayOutputStream replies = new ByteArrayOutputStream();
            DaemonConnectCommand.bridge(socket,
                    new ByteArrayInputStream((Json.write(Map.of("method", "org.example.Test.Count",
                            "parameters", Map.of(), "more", true)) + "\0")
                            .getBytes(StandardCharsets.UTF_8)),
                    replies);

            final String out = replies.toString(StandardCharsets.UTF_8);
            assertThat(out.split("\0")).hasSize(4);
            assertThat(out).contains("\"continues\":true");
        }
    }

    @Test
    void keepsTheFramingByteForByte(@TempDir Path dir) throws Exception {

        // Varlink frames are NUL-separated JSON. A bridge that treated the stream as text - lines,
        // or a charset it decided on - would corrupt exactly the byte that separates two replies.
        final Path socket = dir.resolve("sokard.sock");
        try (VarlinkServer server = answering(socket)) {
            Thread.ofVirtual().start(server);
            waitFor(socket);

            final ByteArrayOutputStream replies = new ByteArrayOutputStream();
            DaemonConnectCommand.bridge(socket,
                    new ByteArrayInputStream((call("org.example.Test.Echo", Map.of("say", "ü \" \n"))
                            ).getBytes(StandardCharsets.UTF_8)),
                    replies);

            assertThat(replies.toByteArray()[replies.size() - 1]).isZero();
        }
    }

    @Test
    void saysSoWhenNoDaemonIsListening(@TempDir Path dir) {

        // On standard error, which ssh keeps separate: a client decoding varlink must not be
        // handed prose on the channel it is decoding.
        // A runtime directory of its own. With none, XDG_RUNTIME_DIR falls back to the real
        // /run/user/<uid>, and this test then passed or failed depending on whether the machine
        // happened to have a daemon socket lying there - a socket file outlives the process that
        // made it, so an acceptance run hours earlier was enough to turn it red.
        final XdgPaths xdg = XdgPaths.of(name ->
                "XDG_RUNTIME_DIR".equals(name) ? dir.resolve("run").toString() : null, dir);
        final SokarContext context = new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        final CommandLine cmd = new CommandLine(new SokarCli(), new SokarFactory(context));
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(err));

        assertThat(cmd.execute("daemon", "connect")).isEqualTo(69);
        assertThat(err.toString()).contains("no daemon socket at").contains("start sokard");
    }

    private static void waitFor(Path socket) throws IOException, InterruptedException {
        for (int i = 0; i < 200 && !java.nio.file.Files.exists(socket); i++) {
            Thread.sleep(10);
        }
        if (!java.nio.file.Files.exists(socket)) {
            throw new IOException("the server never bound " + socket);
        }
    }
}
