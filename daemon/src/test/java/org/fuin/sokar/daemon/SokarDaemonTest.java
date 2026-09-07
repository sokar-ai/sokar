package org.fuin.sokar.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.SokarPaths;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.fuin.sokar.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SokarDaemon}.
 * <p>
 * Driven over a real unix socket with the real client rather than by calling the methods directly:
 * what is being checked is the wire, and a call that never crosses it proves nothing about it.
 */
class SokarDaemonTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /**
     * Runs the server on its own thread and hands the socket to the body.
     */
    private void serving(Path dir, ThrowingConsumer body) throws Exception {
        final Path socket = dir.resolve("sokard.sock");
        try (VarlinkServer server = SokarDaemon.serving(context(dir), socket)) {
            final Thread thread = Thread.ofVirtual().start(server);
            try {
                body.accept(socket);
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(Path socket) throws Exception;
    }

    @Test
    void listsTheTasksOverTheSocket(@TempDir Path dir) throws Exception {

        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n"
                + "sokar-uc-shell-2\tExited (0) 2 minutes ago\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply = client.call(SokarDaemon.INTERFACE + ".List",
                        Map.of());

                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> tasks =
                        (List<Map<String, Object>>) reply.get("tasks");
                assertThat(tasks).hasSize(2);
                assertThat(tasks.get(0)).containsEntry("name", "sokar-uc-shell-1")
                        .containsEntry("running", true);
                assertThat(tasks.get(1)).containsEntry("name", "sokar-uc-shell-2")
                        .containsEntry("running", false);
            }
        });
    }

    @Test
    void stopsATaskOverTheSocket(@TempDir Path dir) throws Exception {

        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        runner.answering("git status --porcelain", "0 0");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply = client.call(SokarDaemon.INTERFACE + ".Stop",
                        Map.of("task", "sokar-uc-shell-1"));

                assertThat(reply).containsEntry("outcome", "STOPPED")
                        .containsEntry("removed", false);
                assertThat(runner.lines()).anyMatch(line -> line.startsWith("podman stop"));
            }
        });
    }

    @Test
    void refusesOverTheSocketExactlyAsTheCliDoes(@TempDir Path dir) throws Exception {

        // The reason this shares TaskControl with the CLI rather than reimplementing it: a
        // refusal that exists in one caller and not the other is a task removed, over this
        // socket, with work that existed nowhere else.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        runner.answering("git status --porcelain", "3 2");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply = client.call(SokarDaemon.INTERFACE + ".Stop",
                        Map.of("task", "sokar-uc-shell-1", "purge", true));

                assertThat(reply).containsEntry("outcome", "HOLDS_WORK")
                        .containsEntry("work", "2 commits and 3 changed files");
                assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
            }
        });
    }

    @Test
    void resumingSomethingAlreadyUpIsAnswered(@TempDir Path dir) throws Exception {

        runner.answering("container inspect", "4711");
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply = client.call(SokarDaemon.INTERFACE + ".Resume",
                        Map.of("task", "sokar-uc-shell-1"));

                assertThat(reply).containsEntry("outcome", "ALREADY_RUNNING");
                assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman start"));
            }
        });
    }

    @Test
    void aCallWithNoTaskNameIsRefusedRatherThanGuessedAt(@TempDir Path dir) throws Exception {

        // A client is not this process: nothing it sends can be assumed to be there.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThat(client.call(SokarDaemon.INTERFACE + ".Stop", Map.of()))
                        .containsEntry("outcome", "NOT_A_TASK");
                assertThat(client.call(SokarDaemon.INTERFACE + ".Resume", Map.of()))
                        .containsEntry("outcome", "NOT_A_TASK");
            }
        });
    }

    @Test
    void theSocketIsOwnerOnly(@TempDir Path dir) throws Exception {

        // The whole access-control story: no listener on any interface, and the filesystem
        // refusing every other account on this machine. A check written in Java could be
        // forgotten; a mode the kernel enforces cannot.
        serving(dir, socket -> {
            final Set<PosixFilePermission> mode = Files.getPosixFilePermissions(socket);
            assertThat(mode).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            assertThat(mode).noneMatch(permission -> permission.name().startsWith("GROUP")
                    || permission.name().startsWith("OTHERS"));
        });
    }

    @Test
    void answersWhatItIsWhenAsked(@TempDir Path dir) throws Exception {

        // Introspection is part of the protocol, which is what lets varlinkctl - or anything else
        // that speaks it - check this service without Sokar writing the checker.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> info = client.call("org.varlink.service.GetInfo",
                        Map.of());
                assertThat(info).containsEntry("product", "Sokar");
                @SuppressWarnings("unchecked")
                final List<String> interfaces = (List<String>) info.get("interfaces");
                assertThat(interfaces).contains(SokarDaemon.INTERFACE);
            }
        });
    }

    @Test
    void aTaskThatRecordedNothingIsStillListed(@TempDir Path dir) throws Exception {

        // A sidecar that cannot be read costs detail, not the row: an operator listing tasks to
        // find one to stop needs the name most of all.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply = client.call(SokarDaemon.INTERFACE + ".List",
                        Map.of());
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> tasks =
                        (List<Map<String, Object>>) reply.get("tasks");
                assertThat(tasks).singleElement()
                        .satisfies(task -> assertThat(task).containsEntry("project", "")
                                .containsEntry("securityClass", ""));
            }
        });
    }

    @Test
    void aSecondDaemonTakesOverTheSocketRatherThanFailing(@TempDir Path dir) throws IOException {

        // Restarting is the common case - an upgrade, a crash - and a stale socket file from the
        // previous one must not be what stops it coming back.
        final Path socket = dir.resolve("sokard.sock");
        try (VarlinkServer first = SokarDaemon.serving(context(dir), socket)) {
            assertThat(socket).exists();
        }
        try (VarlinkServer second = SokarDaemon.serving(context(dir), socket)) {
            assertThat(socket).exists();
        }
    }
}
