package org.fuin.sokar.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.clearance.ClearanceHub;
import org.fuin.sokar.clearance.ClearanceService;
import org.fuin.sokar.clearance.Verdict;
import org.fuin.sokar.app.SokarPaths;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.fuin.sokar.wire.varlink.VarlinkException;
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

    /** Written into and read back out of a vault that only exists for the length of one test. */
    private static final char[] VAULT_PASSPHRASE = "correct horse battery staple".toCharArray();

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
        servingContext(context(dir), dir, body);
    }

    /**
     * Runs the server on a context the caller prepared, for a test that has to set up what the
     * daemon will be asked about before it starts.
     */
    private void servingContext(SokarContext context, Path dir, ThrowingConsumer body)
            throws Exception {
        final Path socket = dir.resolve("sokard.sock");
        try (VarlinkServer server = SokarDaemon.serving(context, socket)) {
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
    void redrawsWhenTheWorkChangesWithoutTheContainerChanging(@TempDir Path dir) throws Exception {

        // The one transition that matters and the runtime cannot see: a task that starts waiting
        // for an answer is still "Up 4 minutes". A watcher comparing only the container would
        // never redraw it.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1788500000\t0\n");
        final Path state = dir.resolve("run/sokar/sokar-uc-shell-1");
        Files.createDirectories(state);

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final List<Map<String, Object>> answers = new CopyOnWriteArrayList<>();
                final Thread reader = Thread.ofVirtual().start(() -> {
                    try {
                        client.callMore(SokarDaemon.INTERFACE + ".Watch", Map.of(), answers::add);
                    } catch (RuntimeException ex) {
                        // Ends with the connection.
                    }
                });

                waitFor(() -> !answers.isEmpty());
                Files.writeString(state.resolve(org.fuin.sokar.wire.Waiting.FILE),
                        "api.example.test:443");

                waitFor(() -> answers.stream()
                        .anyMatch(answer -> String.valueOf(answer).contains("WAITING")));
                assertThat(String.valueOf(answers.getLast()))
                        .contains("api.example.test:443");

                reader.interrupt();
            }
        });
    }

    @Test
    void widensARunningTaskWithoutStoppingIt(@TempDir Path dir) throws Exception {

        // The whole point: an agent an hour into a run needs a host nobody declared, and the
        // alternative is to throw the run away to change a line in a file.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1788500000\t0\n");
        final Path state = dir.resolve("run/sokar/sokar-uc-shell-1");
        Files.createDirectories(state);
        new org.fuin.sokar.wire.Sidecar(org.fuin.sokar.wire.Sidecar.VERSION, "uc", "guarded",
                state.resolve("r.nft").toString(), state.resolve("dns.conf").toString(),
                "/usr/bin/sokar", state.toString()).writeTo(state.resolve("sidecar.json"));
        Files.writeString(state.resolve(org.fuin.sokar.shield.DnsPolicy.SERVERS_FILE),
                "server=/declared.test/192.0.2.53\n");
        Files.writeString(state.resolve("dnsmasq.pid"), "4711");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {

                final Map<String, Object> preview = client.call(
                        SokarDaemon.INTERFACE + ".WidenTask",
                        Map.of("task", "sokar-uc-shell-1", "domains", List.of("docs.example.test"),
                                "scope", "RUN", "dryRun", true));
                assertThat(preview.get("outcome")).isEqualTo("PREVIEWED");
                assertThat(Files.readString(
                        state.resolve(org.fuin.sokar.shield.DnsPolicy.SERVERS_FILE)))
                        .doesNotContain("docs.example.test");

                final Map<String, Object> done = client.call(
                        SokarDaemon.INTERFACE + ".WidenTask",
                        Map.of("task", "sokar-uc-shell-1", "domains", List.of("docs.example.test"),
                                "scope", "RUN"));
                assertThat(done.get("outcome")).isEqualTo("WIDENED");
                assertThat(done.get("persisted")).isEqualTo(false);
                assertThat(Files.readString(
                        state.resolve(org.fuin.sokar.shield.DnsPolicy.SERVERS_FILE)))
                        .contains("server=/docs.example.test/192.0.2.53");
                assertThat(org.fuin.sokar.wire.GrantedNames.all(state))
                        .containsExactly("docs.example.test");
            }
        });
    }

    @Test
    void refusesToWidenWithoutBeingToldHowFar(@TempDir Path dir) throws Exception {

        // "This run only" and "this run and the project" are different intentions. A client that
        // did not say which it meant must not have one picked for it.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThatThrownBy(() -> client.call(SokarDaemon.INTERFACE + ".WidenTask",
                        Map.of("task", "sokar-uc-shell-1",
                                "domains", List.of("docs.example.test"))))
                        .isInstanceOf(VarlinkException.class)
                        .hasMessageContaining("ScopeRequired");
            }
        });
    }

    @Test
    void listsTheEgressSetsAChooserWouldOffer(@TempDir Path dir) throws Exception {

        // Without this an interface cannot offer a chooser, and an operator is back to authoring
        // host lists by hand - which is the thing sets exist to prevent.
        final Path sets = dir.resolve("data/sokar/egress");
        Files.createDirectories(sets);
        Files.writeString(sets.resolve("maven.yaml"),
                "name: maven\nlabel: Maven Central\ndomains:\n  - repo.maven.apache.org\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> answer =
                        client.call(SokarDaemon.INTERFACE + ".Sets", Map.of());

                final Map<?, ?> set = (Map<?, ?>) ((List<?>) answer.get("sets")).getFirst();
                assertThat(set.get("name")).isEqualTo("maven");
                assertThat(set.get("label")).isEqualTo("Maven Central");
                assertThat(String.valueOf(set.get("domains"))).contains("repo.maven.apache.org");
                // Where they come from, in search order: an operator's own file of the same name
                // wins over a packaged one, and that is worth being able to show.
                assertThat((List<?>) answer.get("locations")).isNotEmpty();
            }
        });
    }

    @Test
    void answersWhatAProjectMayReachAndChangesIt(@TempDir Path dir) throws Exception {

        // The pair that makes the egress editor reachable from an interface. Driven over the wire
        // because that is the half that was missing; what the edit does to the file is covered
        // where the edit lives.
        final Path sets = dir.resolve("data/sokar/egress");
        Files.createDirectories(sets);
        Files.writeString(sets.resolve("maven.yaml"),
                "name: maven\nlabel: Maven\ndomains:\n  - repo.maven.apache.org\n");
        final Path projectFile = Files.writeString(dir.resolve("project.yml"), """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """);

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {

                assertThat((List<?>) client.call(SokarDaemon.INTERFACE + ".Egress",
                        Map.of("project", projectFile.toString())).get("hosts")).isEmpty();

                final Map<String, Object> preview = client.call(
                        SokarDaemon.INTERFACE + ".SetEgress",
                        Map.of("project", projectFile.toString(),
                                "addSets", List.of("maven"), "dryRun", true));
                assertThat(preview.get("outcome")).isEqualTo("PREVIEWED");
                assertThat(String.valueOf(preview.get("opens")))
                        .contains("repo.maven.apache.org").contains("set maven");

                // A preview writes nothing, which is the whole of its promise.
                assertThat(Files.readString(projectFile)).doesNotContain("egress");

                assertThat(client.call(SokarDaemon.INTERFACE + ".SetEgress",
                        Map.of("project", projectFile.toString(), "addSets", List.of("maven")))
                        .get("outcome")).isEqualTo("CHANGED");
                assertThat(Files.readString(projectFile)).contains("sets: [maven]");

                assertThat((List<?>) client.call(SokarDaemon.INTERFACE + ".Egress",
                        Map.of("project", projectFile.toString())).get("hosts")).hasSize(1);
            }
        });
    }

    @Test
    void refusesASetTheMachineDoesNotHaveOverTheWire(@TempDir Path dir) throws Exception {

        // Written, the file would name something no task on this machine could resolve, and every
        // run would fail on it rather than this one call.
        final Path projectFile = Files.writeString(dir.resolve("project.yml"), """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """);

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> answer = client.call(
                        SokarDaemon.INTERFACE + ".SetEgress",
                        Map.of("project", projectFile.toString(),
                                "addSets", List.of("nonesuch")));

                assertThat(answer.get("outcome")).isEqualTo("NO_SUCH_SET");
                assertThat(String.valueOf(answer.get("detail"))).contains("nonesuch");
                assertThat(Files.readString(projectFile)).doesNotContain("egress");
            }
        });
    }

    @Test
    void refusesToEditWithoutAProjectFile(@TempDir Path dir) throws Exception {

        // The same refusal the gate methods make: a path is the caller's to give, and one it did
        // not give cannot be guessed at.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThatThrownBy(() -> client.call(SokarDaemon.INTERFACE + ".SetEgress",
                        Map.of("addSets", List.of("maven"))))
                        .isInstanceOf(VarlinkException.class)
                        .hasMessageContaining("ProjectRequired");
            }
        });
    }

    @Test
    void listsTheProjectsThisMachineKnowsAbout(@TempDir Path dir) throws Exception {

        // The method that makes the gate reachable from an interface: every gate call takes a
        // project file path, and a client on another machine has no filesystem here to find one in.
        Files.createDirectories(dir.resolve("data/sokar/mirrors/uc.git"));
        final Path projectFile = Files.writeString(dir.resolve("project.yml"), "project:\n");
        // One file per project, which is how a task start writes it: a shared document would be a
        // read-modify-write that two starts at once can lose or mix.
        Files.createDirectories(dir.resolve("data/sokar/projects"));
        Files.writeString(dir.resolve("data/sokar/projects/uc"), projectFile + "\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Projects", Map.of());

                assertThat((List<?>) reply.get("projects")).hasSize(1);
                final Map<?, ?> project = (Map<?, ?>) ((List<?>) reply.get("projects")).getFirst();
                assertThat(project.get("name")).isEqualTo("uc");
                assertThat(project.get("file")).isEqualTo(projectFile.toString());
                assertThat(String.valueOf(project.get("mirror"))).endsWith("uc.git");
            }
        });
    }

    @Test
    void listsNoProjectsOnAMachineThatHasRunNothing(@TempDir Path dir) throws Exception {

        // Empty rather than an error: a fresh machine is a normal state, and an interface should
        // show "none yet" rather than a fault.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThat((List<?>) client.call(SokarDaemon.INTERFACE + ".Projects", Map.of())
                        .get("projects")).isEmpty();
            }
        });
    }

    @Test
    void listsTheLogsATaskActuallyHas(@TempDir Path dir) throws Exception {

        // Which files exist depends on what the task started, so a client that held a list of
        // names would open an empty viewer for one that was never going to be there.
        final Path state = dir.resolve("run/sokar/sokar-uc-shell-1");
        Files.createDirectories(state);
        Files.writeString(state.resolve("gate.log"), "a line\n");
        Files.writeString(state.resolve("task.log"), "another\n");
        Files.writeString(state.resolve("watcher.pid"), "4711");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply = client.call(SokarDaemon.INTERFACE + ".Logs",
                        Map.of("task", "sokar-uc-shell-1"));

                assertThat((List<?>) reply.get("logs")).hasSize(2);
                assertThat(String.valueOf(reply.get("logs")))
                        .contains("gate.log").contains("task.log")
                        .as("only logs, and the size a viewer needs")
                        .doesNotContain("watcher.pid");
                assertThat(((Map<?, ?>) ((List<?>) reply.get("logs")).getFirst()).get("bytes"))
                        .isNotNull();
            }
        });
    }

    @Test
    void listsNoLogsForATaskThatIsGone(@TempDir Path dir) throws Exception {

        // Removal takes the state directory with it. Empty rather than an error: the task existed,
        // and asking about it is not a mistake a client should have to handle.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThat((List<?>) client.call(SokarDaemon.INTERFACE + ".Logs",
                        Map.of("task", "sokar-uc-shell-1")).get("logs")).isEmpty();
            }
        });
    }

    @Test
    void listsNoLogsForSomethingThatIsNotATask(@TempDir Path dir) throws Exception {

        // The same refusal Tail makes: a name Sokar did not create is not something to read files
        // out of, whatever it points at.
        final Path state = dir.resolve("run/sokar/not-sokar-at-all");
        Files.createDirectories(state);
        Files.writeString(state.resolve("secrets.log"), "nothing to see\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThat((List<?>) client.call(SokarDaemon.INTERFACE + ".Logs",
                        Map.of("task", "not-sokar-at-all")).get("logs")).isEmpty();
            }
        });
    }

    @Test
    void tailsALogAsItIsWritten(@TempDir Path dir) throws Exception {

        // A client that polls lags a prompt that expires, which is why these exist at all.
        final Path state = dir.resolve("run/sokar/sokar-uc-shell-1");
        Files.createDirectories(state);
        final Path log = state.resolve("gate.log");
        Files.writeString(log, "first line\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final List<Map<String, Object>> replies = new CopyOnWriteArrayList<>();
                final Thread reader = Thread.ofVirtual().start(() -> {
                    try {
                        client.callMore(SokarDaemon.INTERFACE + ".Tail",
                                Map.of("task", "sokar-uc-shell-1", "log", "gate.log"),
                                replies::add);
                    } catch (RuntimeException ex) {
                        // The connection closes underneath it, which is how a tail ends.
                    }
                });

                waitFor(() -> !replies.isEmpty());
                Files.writeString(log, "second line\n", java.nio.file.StandardOpenOption.APPEND);
                waitFor(() -> replies.stream().anyMatch(
                        reply -> String.valueOf(reply.get("lines")).contains("second line")));

                reader.interrupt();
                assertThat(replies.stream().map(reply -> String.valueOf(reply.get("lines"))))
                        .anyMatch(lines -> lines.contains("first line"));
            }
        });
    }

    @Test
    void aLogNameIsNotAPath(@TempDir Path dir) throws Exception {

        // A client is not this process, and '../../etc/passwd' is a log name until something
        // refuses it.
        Files.createDirectories(dir.resolve("run/sokar/sokar-uc-shell-1"));

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                for (final String name : List.of("../../../etc/passwd", "/etc/passwd",
                        "gate.log/../../../etc/passwd", "")) {
                    assertThatThrownBy(() -> client.call(SokarDaemon.INTERFACE + ".Tail",
                            Map.of("task", "sokar-uc-shell-1", "log", name)))
                            .as("log name '%s'", name)
                            .isInstanceOf(VarlinkException.class);
                }
            }
        });
    }

    @Test
    void watchingWithoutAskingToStreamAnswersOnce(@TempDir Path dir) throws Exception {

        // One method serves both, so a client that cannot stream is not locked out of the data.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Watch", Map.of());
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> tasks =
                        (List<Map<String, Object>>) reply.get("tasks");
                assertThat(tasks).singleElement()
                        .satisfies(task -> assertThat(task).containsEntry("name",
                                "sokar-uc-shell-1"));
            }
        });
    }

    @Test
    void watchSendsTheStateAgainOnlyWhenItChanges(@TempDir Path dir) throws Exception {

        // A fleet view redrawn every half second because nothing happened is noise.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final List<Map<String, Object>> replies = new CopyOnWriteArrayList<>();
                final Thread reader = Thread.ofVirtual().start(() -> {
                    try {
                        client.callMore(SokarDaemon.INTERFACE + ".Watch", Map.of(), replies::add);
                    } catch (RuntimeException ex) {
                        // Ends with the connection.
                    }
                });

                waitFor(() -> replies.size() == 1);

                // The age moves on its own: 'Up 3 seconds' becomes 'Up 4 seconds' a second later.
                // Measured against a real task, this made the watch fire every second and tell a
                // fleet view to redraw because a clock had moved.
                runner.answering("ps", "sokar-uc-shell-1\tUp 9 minutes\n");
                Thread.sleep(SokarDaemon.WATCH_INTERVAL.multipliedBy(4));
                assertThat(replies).as("only the age changed, so nothing more was sent")
                        .hasSize(1);

                runner.answering("ps", "sokar-uc-shell-1\tExited (0) 1 second ago\n");
                waitFor(() -> replies.size() == 2);

                reader.interrupt();
                assertThat(String.valueOf(replies.get(1))).contains("Exited");
            }
        });
    }

    /**
     * Waits for a condition the server reaches on its own schedule.
     */
    private static void waitFor(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the server never reached the expected state");
    }

    @Test
    void carriesAPromptFromATaskAndTheAnswerBack(@TempDir Path dir) throws Exception {

        // The one socket an interface talks to. Driven against a real watcher-side service on a
        // real socket, because what is being checked is that two processes meet - and a prompt
        // that never arrives, or an answer that goes nowhere, both leave the task blocked.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        final Path state = dir.resolve("run/sokar/sokar-uc-shell-1");
        Files.createDirectories(state);

        final List<String> decided = new CopyOnWriteArrayList<>();
        final ClearanceHub hub = new ClearanceHub("uc", "shell", request -> Verdict.TIMEOUT,
                (address, name) -> decided.add(address));

        try (ClearanceService watcher = new ClearanceService(state.resolve("clearance.sock"), hub)) {
            watcher.start();

            serving(dir, socket -> {
                try (VarlinkClient client = new VarlinkClient(socket)) {
                    final List<Map<String, Object>> prompts = new CopyOnWriteArrayList<>();
                    final Thread reader = Thread.ofVirtual().start(() -> {
                        try {
                            client.callMore(SokarDaemon.INTERFACE + ".Prompts", Map.of(),
                                    prompts::add);
                        } catch (RuntimeException ex) {
                            // Ends with the connection.
                        }
                    });

                    // Wait until the daemon has subscribed, then report a blocked destination the
                    // way the reader hook does.
                    waitFor(() -> watcher.subscriberCount() > 0);
                    try (VarlinkClient hook =
                            new VarlinkClient(state.resolve("clearance.sock"))) {
                        hook.call(ClearanceService.INTERFACE + ".Report",
                                Map.of("destination", "pypi.org", "protocol", "tcp",
                                        "port", 443));
                    }

                    waitFor(() -> !prompts.isEmpty());
                    assertThat(prompts.get(0))
                            .containsEntry("destination", "pypi.org")
                            .as("tagged with the task it came from, so an answer can go back")
                            .containsEntry("task", "sokar-uc-shell-1");

                    // Nobody answered, and that arrives too. Without it an interface cannot tell a
                    // question that ran out from one still waiting, and this one will never be
                    // asked again.
                    waitFor(() -> prompts.size() > 1);
                    assertThat(prompts.get(1))
                            .containsEntry("destination", "pypi.org")
                            .containsEntry("verdict", "timeout")
                            .containsEntry("task", "sokar-uc-shell-1");

                    // And the answer reaches the hub that is holding the task.
                    try (VarlinkClient answering = new VarlinkClient(socket)) {
                        assertThat(answering.call(SokarDaemon.INTERFACE + ".Decide",
                                Map.of("task", "sokar-uc-shell-1", "key", "tcp/pypi.org/443",
                                        "address", "pypi.org", "allow", true)))
                                .containsEntry("ok", true);
                    }
                    waitFor(() -> decided.contains("pypi.org"));

                    reader.interrupt();
                }
            });
        }
    }

    @Test
    void answeringATaskWithNoWatcherSaysSo(@TempDir Path dir) throws Exception {

        // An answer that goes nowhere leaves the operator believing they unblocked something.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThatThrownBy(() -> client.call(SokarDaemon.INTERFACE + ".Decide",
                        Map.of("task", "sokar-uc-shell-1", "key", "k", "address", "a",
                                "allow", true)))
                        .isInstanceOf(VarlinkException.class);
            }
        });
    }

    @Test
    void promptsWithoutStreamingIsRefusedRatherThanAnsweredOnce(@TempDir Path dir)
            throws Exception {

        // Answering a stream once would look like it worked and then deliver nothing ever again.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThatThrownBy(() -> client.call(SokarDaemon.INTERFACE + ".Prompts", Map.of()))
                        .isInstanceOf(VarlinkException.class);
            }
        });
    }

    @Test
    void listsWhatTheVaultHoldsWithoutItsValues(@TempDir Path dir) throws Exception {

        // The whole credential promise in one assertion: what is stored can be shown without ever
        // displaying, logging or copying a value.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Credentials", Map.of());
                assertThat(reply).containsKey("vault").containsKey("credentials")
                        .containsEntry("exists", false);
                assertThat(String.valueOf(reply)).doesNotContain("value");
            }
        });
    }

    @Test
    void anUnlockedVaultHoldingNothingIsNotReportedAsLocked(@TempDir Path dir)
            throws Exception {

        // The whole reason 'readable' exists, and it used to answer this case wrongly: the field
        // was inferred from the credential list being empty, so a vault that was open and simply
        // empty was indistinguishable from one nobody had unlocked. A client acting on that told
        // somebody to unlock a vault that was already open.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.sokar.vault.KernelKeyring.available(), "libkeyutils is not installed");

        final SokarContext context = context(dir);
        final org.fuin.sokar.vault.KernelKeyring keyring =
                new org.fuin.sokar.vault.KernelKeyring(context.paths().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), VAULT_PASSPHRASE);
            keyring.store(VAULT_PASSPHRASE);

            servingContext(context, dir, socket -> {
                try (VarlinkClient client = new VarlinkClient(socket)) {
                    final Map<String, Object> reply =
                            client.call(SokarDaemon.INTERFACE + ".Credentials", Map.of());
                    assertThat(reply).containsEntry("exists", true)
                            .containsEntry("readable", true)
                            .containsEntry("credentials", List.of());
                }
            });
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aLockedVaultIsReportedAsUnreadable(@TempDir Path dir) throws Exception {

        // The other half of the same distinction: nothing is in the keyring, so the daemon cannot
        // read it and has no terminal to ask at. "We cannot tell you until you unlock it" and
        // "it is not there" are different sentences, and only one of them is somebody's problem.
        final SokarContext context = context(dir);
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of("anthropic",
                org.fuin.sokar.vault.VaultEntry.of("sk-test-value")), VAULT_PASSPHRASE);

        servingContext(context, dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Credentials", Map.of());
                assertThat(reply).containsEntry("exists", true)
                        .containsEntry("readable", false)
                        .containsEntry("credentials", List.of());
            }
        });
    }

    @Test
    void panicListsWhatItWouldStopAndStopsNothing(@TempDir Path dir) throws Exception {

        // The half somebody presses first, and the half that must not act. A preview that stopped
        // anything would be the worst possible surprise in the one command reached for when
        // nobody knows what is wrong.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n"
                + "sokar-uc-shell-2\tExited (0) 2 minutes ago\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply = client.call(SokarDaemon.INTERFACE + ".Panic",
                        Map.of("dryRun", true));

                assertThat(reply).containsEntry("previewed", true);
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> tasks =
                        (List<Map<String, Object>>) reply.get("tasks");
                // Only the running one: a task that has exited is not something to stop.
                assertThat(tasks).hasSize(1);
                assertThat(tasks.getFirst()).containsEntry("name", "sokar-uc-shell-1");
            }
        });

        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman stop"));
    }

    @Test
    void panicStopsEveryRunningTaskAndRemovesNothing(@TempDir Path dir) throws Exception {

        // It stops; it never removes. An operator who believed this cleaned up would go looking
        // for work that is still exactly where it was, which is why nothing here may run 'rm'.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n"
                + "sokar-uc-other-2\tUp 9 minutes\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Panic", Map.of());

                assertThat(reply).containsEntry("previewed", false);
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> tasks =
                        (List<Map<String, Object>>) reply.get("tasks");
                assertThat(tasks).hasSize(2);
                assertThat(tasks.stream().map(task -> task.get("name")))
                        .containsExactly("sokar-uc-shell-1", "sokar-uc-other-2");
            }
        });

        assertThat(runner.lines()).anyMatch(line -> line.startsWith("podman stop"));
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void lockSaysWhatItDidAndWhatItCouldNotReach(@TempDir Path dir) throws Exception {

        // A running task's proxy read the credential when it started and holds it in its own
        // memory, where locking cannot reach. Reporting "locked" without saying so would claim
        // more than happened.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Lock", Map.of());

                assertThat(reply).containsKeys("keyring", "wasCached", "holding");
                // Compared as a number, not by type: the reader turns every JSON number into a
                // Double by design, so the wire's "1" arrives as 1.0 for every int in this
                // contract.
                assertThat(((Number) reply.get("holding")).longValue()).isEqualTo(1L);
                // Nothing was ever cached for this temporary vault, so locking it changed nothing
                // - which is not a failure: "not cached" is the wanted state either way.
                assertThat(reply).containsEntry("wasCached", false);
            }
        });
    }

    @Test
    void agentsNamesTheInstalledCopyThatNeverRuns(@TempDir Path dir) throws Exception {

        // A shadowed binary is never started, so it has no agent entry to carry a flag - which is
        // why it is reported separately rather than as "inUse: false" on something that is not
        // there. The interface asked for this after building a view for a state that could not
        // occur; the honest shape is the pair, so a person is told what runs instead.
        final Path own = Files.createDirectories(dir.resolve("data/sokar/agents"));
        final Path packaged = Files.createDirectories(dir.resolve("packaged"));
        for (final Path where : List.of(own, packaged)) {
            final Path binary = Files.createFile(
                    where.resolve(org.fuin.sokar.agent.api.AgentDirectory.PREFIX + "alpha"));
            assertThat(binary.toFile().setExecutable(true)).isTrue();
        }

        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(runner,
                new SokarPaths(xdg, dir.resolve("bin"), dir.resolve("hooks"), packaged),
                arguments -> 0);

        servingContext(context, dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Agents", Map.of());

                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> shadowed =
                        (List<Map<String, Object>>) reply.get("shadowed");
                assertThat(shadowed).hasSize(1);
                assertThat(shadowed.getFirst())
                        .containsEntry("path", packaged.resolve("sokar-agent-alpha").toString())
                        .containsEntry("usedInstead", own.resolve("sokar-agent-alpha").toString());
            }
        });
    }

    /** Puts a provider on the machine, so a listing of them has something to list. */
    private void declareProvider(Path dir) throws Exception {
        final Path providers = Files.createDirectories(dir.resolve("data/sokar/providers"));
        Files.writeString(providers.resolve("anthropic.yaml"), """
                name: anthropic
                label: Anthropic
                upstream: https://api.anthropic.com
                dialects:
                  anthropic-messages: ""
                auth_header:
                  _default: x-api-key
                auth_prefix:
                  _default: ""
                token_env:
                  _default: ANTHROPIC_API_KEY
                """);
    }

    private static org.fuin.sokar.agent.api.ProviderDefinition anthropic() {
        return new org.fuin.sokar.agent.api.ProviderDefinition("anthropic", "Anthropic",
                "https://api.anthropic.com", Map.of("anthropic-messages", ""),
                Map.of("_default", "x-api-key"), Map.of("_default", ""), Map.of(),
                Map.of("_default", "ANTHROPIC_API_KEY"));
    }

    private static org.fuin.sokar.vault.VaultEntry entry(String type) {
        return new org.fuin.sokar.vault.VaultEntry("sk-secret", type);
    }

    @Test
    void aCredentialStoredUnderAnAgentsOwnNameIsStillFound() {

        // A vault written before credentials were keyed by provider. The key stays the agent's,
        // so upgrading does not stop anybody authenticating - and an interface that recomputed
        // this from the provider list alone would report a missing credential for exactly the
        // vault that has one.
        final Map<String, Object> row = SokarDaemon.provider(anthropic(),
                Map.of("claude", entry("oauth")), "claude");

        assertThat(row).containsEntry("credentialName", "claude")
                .containsEntry("authenticated", true)
                .containsEntry("credentialType", "oauth")
                .containsEntry("storeCommand", "sokar vault put claude");
    }

    @Test
    void theProvidersOwnNameWinsWhenBothAreStored() {

        // Two agents reaching one provider must find one entry. Where both names exist the
        // provider's is the live one, and the agent's is a leftover.
        final Map<String, Object> row = SokarDaemon.provider(anthropic(),
                Map.of("claude", entry("oauth"), "anthropic", entry("api-key")), "claude");

        assertThat(row).containsEntry("credentialName", "anthropic")
                .containsEntry("credentialType", "api-key");
    }

    @Test
    void anEmptyVaultPointsAtWhereANewCredentialBelongs() {

        final Map<String, Object> row = SokarDaemon.provider(anthropic(), Map.of(), null);

        assertThat(row).containsEntry("credentialName", "anthropic")
                .containsEntry("authenticated", false)
                .containsEntry("credentialType", "")
                .containsEntry("storeCommand", "sokar vault put anthropic");
    }

    @Test
    void aStoredCredentialOfUnstatedKindReadsAsEmptyRatherThanNull() {

        // "" and not the four characters "null", which an interface would render as a kind.
        final Map<String, Object> row = SokarDaemon.provider(anthropic(),
                Map.of("anthropic", entry(null)), null);

        assertThat(row).containsEntry("credentialType", "").containsEntry("authenticated", true);
    }

    @Test
    void providersNeverAnswerWithACredentialValue(@TempDir Path dir) throws Exception {

        // The invariant the whole contract rests on. A field carrying a value would be one an
        // interface could render, log or put in a crash report without anybody deciding to.
        declareProvider(dir);
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Providers", Map.of());

                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> providers =
                        (List<Map<String, Object>>) reply.get("providers");
                assertThat(providers).allSatisfy(provider ->
                        assertThat(provider.keySet()).containsExactlyInAnyOrder("name", "label",
                                "upstream", "dialects", "authenticated", "credentialType",
                                "credentialName", "storeCommand"));
                assertThat(providers).as("a vacuous pass here would prove nothing").isNotEmpty();
                // A vault that does not exist is readable and empty. Reporting it unreadable
                // would send somebody to unlock a store that is not there - the one instruction
                // that cannot help them.
                assertThat(reply).containsEntry("readable", true);
            }
        });
    }

    @Test
    void providersNameTheKeyAndTheCommandThatStoresOne(@TempDir Path dir) throws Exception {

        // storeCommand is the whole answer to "where do I type it", and it is rendered verbatim.
        // A wrong key name makes that advice useless in the way that is hardest to notice: the
        // command succeeds and stores the credential where nothing will look for it.
        declareProvider(dir);
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> providers = (List<Map<String, Object>>)
                        client.call(SokarDaemon.INTERFACE + ".Providers", Map.of())
                                .get("providers");

                assertThat(providers).singleElement().satisfies(provider -> {
                    assertThat(provider).containsEntry("name", "anthropic")
                            .containsEntry("label", "Anthropic")
                            .containsEntry("upstream", "https://api.anthropic.com")
                            .containsEntry("dialects", List.of("anthropic-messages"))
                            // Nothing is stored, so the key is where a new one belongs.
                            .containsEntry("credentialName", "anthropic")
                            .containsEntry("authenticated", false)
                            .containsEntry("credentialType", "")
                            .containsEntry("storeCommand", "sokar vault put anthropic");
                });
            }
        });
    }

    @Test
    void doctorAnswersEveryProbeWithANextActionForTheOnesThatFailed(@TempDir Path dir)
            throws Exception {

        // The whole value of this report is that each failing line ends with the one thing to do
        // about it - the dependencies it covers all fail far from their cause. A probe that came
        // back with an empty action would be a line an interface renders as a blank where the
        // answer belongs.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Doctor", Map.of());

                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> probes =
                        (List<Map<String, Object>>) reply.get("probes");
                assertThat(probes).isNotEmpty();
                assertThat(probes).allSatisfy(probe -> {
                    assertThat(probe.get("name")).asString().isNotBlank();
                    assertThat(probe.get("state")).asString()
                            .isIn("OK", "DEGRADED", "MISSING", "UNKNOWN");
                    if (!"OK".equals(probe.get("state"))) {
                        assertThat(probe.get("action")).asString()
                                .as("a failing probe must name the next action").isNotBlank();
                    }
                });
            }
        });
    }

    @Test
    void doctorIsNotReadyExactlyWhenSomethingIsMissing(@TempDir Path dir) throws Exception {

        // 'ready' must mean what the CLI's exit code means. Two summaries of one machine that can
        // disagree is worse than one, because whichever a person saw last is the one they act on.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Doctor", Map.of());

                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> probes =
                        (List<Map<String, Object>>) reply.get("probes");
                final boolean anythingMissing = probes.stream()
                        .anyMatch(probe -> "MISSING".equals(probe.get("state")));

                assertThat(reply.get("ready")).isEqualTo(!anythingMissing);
            }
        });
    }

    @Test
    void nodeAnswersTheSameIdentityToEveryCaller(@TempDir Path dir) throws Exception {

        // Two clients reaching one node by different routes have to be able to tell that it is one
        // node. If this differed per call they would conclude the opposite of the truth.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Object first = client.call(SokarDaemon.INTERFACE + ".Node", Map.of())
                        .get("id");
                final Object second = client.call(SokarDaemon.INTERFACE + ".Node", Map.of())
                        .get("id");

                assertThat(first).asString().isNotBlank();
                assertThat(first).isEqualTo(second);
            }
        });
    }

    @Test
    void agentsSaysNothingIsShadowedWhenNothingIs(@TempDir Path dir) throws Exception {

        // A field that always names something teaches an interface to ignore it.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".Agents", Map.of());
                assertThat(reply).containsEntry("shadowed", List.of());
            }
        });
    }

    @Test
    void aVerifiedArtifactCarriesItsDigestAndNoReason() {

        // Only two artifacts can exist: InstallArtifact refuses one with neither a digest nor a
        // stated reason, which is worth knowing before designing a screen for a third state. It
        // refused the one this test first asserted.
        final Map<String, Object> row = SokarDaemon.artifact(
                new org.fuin.sokar.agent.api.InstallArtifact("https://example.test/tool",
                        "a".repeat(64), "/usr/local/bin/tool", "0755", false, null));

        assertThat(row).containsEntry("sha256", "a".repeat(64))
                .containsEntry("unverified", false)
                // "" and not the four characters "null", which would render as a reason.
                .containsEntry("reason", "")
                .containsEntry("url", "https://example.test/tool")
                .containsEntry("target", "/usr/local/bin/tool");
    }

    @Test
    void anArtifactThatIsUnverifiableOnPurposeKeepsItsReason() {

        // Three renderable states rather than one blank field: verified, nothing to fetch, and
        // unverifiable for a stated reason. The last is a decision somebody made, not a fault,
        // and it reads as a fault without the reason beside it.
        final Map<String, Object> row = SokarDaemon.artifact(
                new org.fuin.sokar.agent.api.InstallArtifact("https://example.test/tool", null,
                        "/usr/local/bin/tool", "0755", true, "the vendor publishes no digest"));

        assertThat(row).containsEntry("unverified", true)
                .containsEntry("reason", "the vendor publishes no digest")
                // Absent as "", never as "null": an interface cannot tell that from a digest.
                .containsEntry("sha256", "");
    }

    @Test
    void canStartAnswersRatherThanThrowingWhenNothingCanRun(@TempDir Path dir) throws Exception {

        // The whole point of the method: a refusal is an ANSWER a client branches on, not an
        // exception and not an exit code with prose after it. F08's sixth criterion is that a
        // missing credential is reported before anything is built or started, and reporting it
        // as a thrown error would put the client back to reading text.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply =
                        client.call(SokarDaemon.INTERFACE + ".CanStart", Map.of());

                assertThat(reply).containsEntry("ready", false)
                        .containsEntry("outcome", "NO_AGENT")
                        .containsEntry("agent", "").containsEntry("credential", "");
                assertThat(String.valueOf(reply.get("detail"))).isNotBlank();
            }
        });
    }

    @Test
    void canStartNamesTheProjectFileItCouldNotRead(@TempDir Path dir) throws Exception {

        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply = client.call(SokarDaemon.INTERFACE + ".CanStart",
                        Map.of("project", dir.resolve("nowhere.yml").toString()));

                assertThat(reply).containsEntry("outcome", "NO_PROJECT_FILE");
                assertThat(String.valueOf(reply.get("detail"))).contains("nowhere.yml");
            }
        });
    }

    @Test
    void labellingSomethingThatIsNotATaskAnswersRatherThanThrows(@TempDir Path dir)
            throws Exception {

        // An outcome a client branches on, like every other refusal here. A thrown error would
        // put the interface back to reading text to find out what happened.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Map<String, Object> reply = client.call(SokarDaemon.INTERFACE + ".Label",
                        Map.of("task", "not-a-sokar-container", "label", "anything"));

                assertThat(reply).containsEntry("outcome", "NOT_A_TASK")
                        .containsEntry("label", "");
            }
        });
    }

    @Test
    void aGateCallWithoutAProjectIsRefused(@TempDir Path dir) throws Exception {

        // A gate belongs to a project, and answering about the wrong one is worse than refusing.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThatThrownBy(() -> client.call(SokarDaemon.INTERFACE + ".Pending", Map.of()))
                        .isInstanceOf(VarlinkException.class);
            }
        });
    }

    @Test
    void approvingWithoutABranchIsRefused(@TempDir Path dir) throws Exception {

        // The single call that sends anything anywhere makes the caller name where.
        serving(dir, socket -> {
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThatThrownBy(() -> client.call(SokarDaemon.INTERFACE + ".Approve",
                        Map.of("project", "project.yml", "name", "shell")))
                        .isInstanceOf(VarlinkException.class);
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
