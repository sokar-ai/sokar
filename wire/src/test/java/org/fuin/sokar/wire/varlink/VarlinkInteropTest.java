package org.fuin.sokar.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the server against systemd's {@code varlinkctl}.
 * <p>
 * This is the test that is actually worth having. The client and server in this package were
 * written by the same hand, so they agreeing proves only that they are self-consistent;
 * {@code varlinkctl} is an entirely independent implementation and will reject anything that only
 * looks like varlink. It is free, so there is no reason not to run it.
 * <p>
 * Skipped where {@code varlinkctl} is not installed rather than failing: it ships with systemd 255
 * and later, and a machine without it is not a machine with a broken Sokar.
 * <p>
 * Runs the process itself rather than through the core runner, because this module has no
 * dependencies and is meant to keep it that way - the agent SPI resolves it.
 */
class VarlinkInteropTest {

    private static final String INTERFACE = "org.fuin.sokar.Clearance1";

    /** What a finished process said, and whether it succeeded. */
    private record Result(String standardOutput, String standardError, boolean successful) {
    }

    /** Streams go to files so neither can fill a pipe while the other is being read. */
    private Result run(Path dir, String... argv) {
        try {
            final Path out = Files.createTempFile(dir, "out", null);
            final Path err = Files.createTempFile(dir, "err", null);
            final Process process = new ProcessBuilder(argv)
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            if (!process.waitFor(java.time.Duration.ofSeconds(30))) {
                process.destroyForcibly();
                throw new IllegalStateException("varlinkctl did not finish: " + List.of(argv));
            }
            return new Result(Files.readString(out), Files.readString(err), process.exitValue() == 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private boolean available(Path dir) {
        return run(dir, "sh", "-c", "command -v varlinkctl").successful();
    }

    private VarlinkServer server(Path dir) {
        final VarlinkServer server = new VarlinkServer(dir.resolve("interop.sock"), INTERFACE);
        server.method("Ping", (parameters, replies) -> replies.last(Map.of("pong", Boolean.TRUE)));
        server.method("Stream", (parameters, replies) -> {
            for (int i = 1; i <= 3; i++) {
                replies.more(Map.of("seq", Integer.valueOf(i)));
            }
            replies.last(Map.of("seq", Integer.valueOf(4)));
        });
        Thread.ofVirtual().start(server);
        return server;
    }

    @Test
    void systemdCanIntrospectTheService(@TempDir Path dir) throws IOException {

        assumeTrue(available(dir), "varlinkctl is not installed");

        try (VarlinkServer server = server(dir)) {
            final Result result = run(dir, "varlinkctl", "info", server.socketPath().toString());

            assertThat(result.standardOutput() + result.standardError()).contains("Sokar");
            assertThat(result.successful()).isTrue();
        }
    }

    @Test
    void systemdCanCallAMethod(@TempDir Path dir) throws IOException {

        assumeTrue(available(dir), "varlinkctl is not installed");

        try (VarlinkServer server = server(dir)) {
            final Result result = run(dir, "varlinkctl", "call", server.socketPath().toString(),
                    INTERFACE + ".Ping", "{}");

            assertThat(result.standardOutput()).contains("pong");
            assertThat(result.successful()).isTrue();
        }
    }

    @Test
    void systemdConsumesTheContinuesFlag(@TempDir Path dir) throws IOException {

        assumeTrue(available(dir), "varlinkctl is not installed");

        try (VarlinkServer server = server(dir)) {
            final Result result = run(dir, "varlinkctl", "--more", "call",
                    server.socketPath().toString(), INTERFACE + ".Stream", "{}");

            // Four replies, and varlinkctl returning rather than hanging is the part that says
            // the 'continues' flag was written the way the protocol expects.
            final List<String> lines = result.standardOutput().lines()
                    .filter(line -> line.contains("seq")).toList();
            assertThat(lines).hasSize(4);
            assertThat(result.successful()).isTrue();
        }
    }
}
