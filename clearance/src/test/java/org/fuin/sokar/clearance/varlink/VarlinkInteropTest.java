package org.fuin.sokar.clearance.varlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.ProcessCommandRunner;
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
 */
class VarlinkInteropTest {

    private static final String INTERFACE = "org.fuin.sokar.Clearance1";

    private final ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(30));

    private boolean available() {
        return runner.run(Command.of("sh", "-c", "command -v varlinkctl")).successful();
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

        assumeTrue(available(), "varlinkctl is not installed");

        try (VarlinkServer server = server(dir)) {
            final CommandResult result = runner.run(Command.of(
                    "varlinkctl", "info", server.socketPath().toString()));

            assertThat(result.standardOutput() + result.standardError()).contains("Sokar");
            assertThat(result.successful()).isTrue();
        }
    }

    @Test
    void systemdCanCallAMethod(@TempDir Path dir) throws IOException {

        assumeTrue(available(), "varlinkctl is not installed");

        try (VarlinkServer server = server(dir)) {
            final CommandResult result = runner.run(Command.of(
                    "varlinkctl", "call", server.socketPath().toString(),
                    INTERFACE + ".Ping", "{}"));

            assertThat(result.standardOutput()).contains("pong");
            assertThat(result.successful()).isTrue();
        }
    }

    @Test
    void systemdConsumesTheContinuesFlag(@TempDir Path dir) throws IOException {

        assumeTrue(available(), "varlinkctl is not installed");

        try (VarlinkServer server = server(dir)) {
            final CommandResult result = runner.run(Command.of(
                    "varlinkctl", "--more", "call", server.socketPath().toString(),
                    INTERFACE + ".Stream", "{}"));

            // Four replies, and varlinkctl returning rather than hanging is the part that says
            // the 'continues' flag was written the way the protocol expects.
            final List<String> lines = result.standardOutput().lines()
                    .filter(line -> line.contains("seq")).toList();
            assertThat(lines).hasSize(4);
            assertThat(result.successful()).isTrue();
        }
    }
}
