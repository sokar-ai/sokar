package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.sokar.agent.api.Waiting;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AgentWaiting}: whether a task waits for a person, read from outside by its agent's own
 * declaration - and every way of saying "cannot tell" kept apart from "not waiting".
 */
class AgentWaitingTest {

    private static final String TASK = "sokar-p-t";

    private static final Waiting DECLARED = new Waiting(
            List.of(new Waiting.Rule("Trust this folder?", 3, "whether to trust the folder")),
            List.of(new Waiting.Rule("(END)", 1, null)),
            new Waiting.LastMessage(Map.of("type", "result"), "result"));

    @TempDir
    Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final MovingClock clock = new MovingClock();

    private final AtomicInteger loaded = new AtomicInteger();

    private AgentWaiting waiting(Map<String, Optional<Waiting>> declarations) {
        final XdgPaths xdg = XdgPaths.of(name -> "XDG_DATA_HOME".equals(name) ? dir.resolve("data").toString() : null,
                dir);
        final SokarContext context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        return new AgentWaiting(context, clock, () -> {
            loaded.incrementAndGet();
            return declarations;
        });
    }

    private AgentWaiting declared() {
        return waiting(Map.of("asker", Optional.of(DECLARED), "quiet", Optional.empty()));
    }

    private AgentWaiting.Derived attached(AgentWaiting waiting) {
        return waiting.about(TASK, true, "asker", "AGENT", dir);
    }

    private long captures() {
        return runner.invocations().stream().filter(command -> command.toString().contains("capture-pane")).count();
    }

    @Test
    void manyTasksScreensAreReadAtOnceSoAListingWaitsForTheSlowestFewNotForAllInTurn() throws Exception {

        // A listing read each task's screen in turn, about 100 ms each - 3.8 s at 30 tasks.
        final java.util.concurrent.atomic.AtomicInteger now = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger most = new java.util.concurrent.atomic.AtomicInteger();
        final org.fuin.sokar.core.process.CommandRunner slow = command -> {
            most.accumulateAndGet(now.incrementAndGet(), Math::max);
            try {
                Thread.sleep(200);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                now.decrementAndGet();
            }
            return new org.fuin.sokar.core.process.CommandResult(command, 0, "stub: at work, nothing asked\n", "");
        };
        final XdgPaths xdg = XdgPaths.of(name -> "XDG_DATA_HOME".equals(name) ? dir.resolve("data").toString() : null,
                dir);
        final AgentWaiting waiting = new AgentWaiting(new SokarContext(slow, new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0), clock, () -> Map.of("asker", Optional.of(DECLARED)));
        final List<AgentWaiting.Wanted> tasks = java.util.stream.IntStream.range(0, 16)
                .mapToObj(i -> new AgentWaiting.Wanted("sokar-p-" + i, true, "asker", "AGENT")).toList();

        final long started = System.nanoTime();
        waiting.readScreens(tasks);
        final java.time.Duration took = java.time.Duration.ofNanos(System.nanoTime() - started);

        assertThat(most.get()).as("several at once, never more than the bound").isGreaterThan(1)
                .isLessThanOrEqualTo(AgentWaiting.AT_ONCE);
        assertThat(took).as("16 reads of 200 ms").isLessThan(java.time.Duration.ofMillis(1600));
        final int before = now.get();
        for (final AgentWaiting.Wanted task : tasks) {
            assertThat(waiting.about(task.container(), true, "asker", "AGENT", dir).screen())
                    .isEqualTo(AgentWaiting.Screen.NOT_WAITING);
        }
        assertThat(most.get()).as("answered from what was read").isLessThanOrEqualTo(AgentWaiting.AT_ONCE);
        assertThat(now.get()).isEqualTo(before);
    }

    // --- the screen

    @Test
    void anAttachedAgentShowingADeclaredQuestionIsWaitingAndSaysWhatFor() {
        runner.answering("capture-pane", "stub: starting\nTrust this folder? [y/N]\n\n\n");

        final AgentWaiting.Derived derived = attached(declared());

        assertThat(derived.screen()).isEqualTo(AgentWaiting.Screen.WAITING);
        assertThat(derived.waitingFor()).isEqualTo("whether to trust the folder");
    }

    @Test
    void aScreenTheTaskWroteIsReadWithoutAskingTheTask() throws Exception {

        // One read was a process in the container, 100 ms and 400-600 ms under load.
        final Path written = ScreenFile.prepare(dir);
        Files.writeString(written.resolve(ScreenFile.SCREEN), "stub: starting\nTrust this folder? [y/N]\n\n\n");
        Files.writeString(written.resolve(ScreenFile.ALIVE), "");
        Files.setLastModifiedTime(written.resolve(ScreenFile.ALIVE),
                java.nio.file.attribute.FileTime.from(clock.instant()));

        final AgentWaiting.Derived derived = attached(declared());

        assertThat(derived.screen()).isEqualTo(AgentWaiting.Screen.WAITING);
        assertThat(derived.waitingFor()).isEqualTo("whether to trust the folder");
        assertThat(captures()).as("no process in the task").isZero();
    }

    @Test
    void anAttachedAgentAtWorkIsNotWaiting() {
        runner.answering("capture-pane", "stub: at work, nothing asked\n");

        assertThat(attached(declared()).screen()).isEqualTo(AgentWaiting.Screen.NOT_WAITING);
    }

    @Test
    void anAgentThatDeclaresNothingCannotBeToldRatherThanReadAsNotWaitingAndIsNotLookedAt() {
        runner.answering("capture-pane", "Trust this folder? [y/N]\n");

        final AgentWaiting.Derived derived = declared().about(TASK, true, "quiet", "AGENT", dir);

        assertThat(derived.screen()).isEqualTo(AgentWaiting.Screen.UNDECLARED).isNotEqualTo(AgentWaiting.Screen.NOT_WAITING);
        assertThat(captures()).isZero();
    }

    @Test
    void onlyAnAttachedRunningAgentHasAScreenToRead() {
        runner.answering("capture-pane", "Trust this folder? [y/N]\n");
        final AgentWaiting waiting = declared();

        assertThat(waiting.about(TASK, true, "asker", "UNATTENDED", dir).screen()).isEqualTo(AgentWaiting.Screen.UNSEEN);
        assertThat(waiting.about(TASK, true, "asker", "SHELL", dir).screen()).isEqualTo(AgentWaiting.Screen.UNSEEN);
        assertThat(waiting.about(TASK, false, "asker", "AGENT", dir).screen()).isEqualTo(AgentWaiting.Screen.UNSEEN);
        assertThat(captures()).isZero();
    }

    @Test
    void aSessionThatCannotBeReadIsUnseen() {
        runner.failing("capture-pane", 1, "no server running");

        assertThat(attached(declared()).screen()).isEqualTo(AgentWaiting.Screen.UNSEEN);
    }

    @Test
    void aPagerOverTheAgentKeepsWhatWasLastReadOfTheAgent() {
        final AgentWaiting waiting = declared();
        runner.answering("capture-pane", "Trust this folder? [y/N]\n");
        assertThat(attached(waiting).screen()).isEqualTo(AgentWaiting.Screen.WAITING);

        runner.answering("capture-pane", "Trust this folder? [y/N]\nsome transcript\n(END)\n");
        clock.move(AgentWaiting.SCREEN);

        assertThat(attached(waiting).screen()).as("reporting on the pager would be wrong either way")
                .isEqualTo(AgentWaiting.Screen.WAITING);
    }

    @Test
    void aPagerBeforeAnythingWasReadOfTheAgentSaysNothing() {
        runner.answering("capture-pane", "some transcript\n(END)\n");

        assertThat(attached(declared()).screen()).isEqualTo(AgentWaiting.Screen.UNSEEN);
    }

    @Test
    void readsAScreenAtMostOnceInItsIntervalWhateverAWatchAsks() {
        runner.answering("capture-pane", "stub: at work, nothing asked\n");
        final AgentWaiting waiting = declared();

        attached(waiting);
        clock.move(Duration.ofMillis(500));
        attached(waiting);
        clock.move(Duration.ofMillis(500));
        attached(waiting);
        assertThat(captures()).isEqualTo(1);

        clock.move(AgentWaiting.SCREEN);
        attached(waiting);
        assertThat(captures()).isEqualTo(2);
    }

    @Test
    void readsTheDeclarationsOnceInTheirIntervalNotOncePerTask() {
        runner.answering("capture-pane", "stub: at work, nothing asked\n");
        final AgentWaiting waiting = declared();

        attached(waiting);
        waiting.about("sokar-p-other", true, "asker", "AGENT", dir);
        assertThat(loaded).hasValue(1);

        clock.move(AgentWaiting.DECLARATIONS);
        attached(waiting);
        assertThat(loaded).as("an agent updated under a running daemon is read again").hasValue(2);
    }

    // --- a declaration that stopped fitting

    @Test
    void aDeclarationThatNeverMatchedInADayIsReportedAsUnproven() {
        runner.answering("capture-pane", "stub: at work, nothing asked\n");
        final AgentWaiting waiting = declared();

        assertThat(attached(waiting).unproven()).isFalse();
        clock.move(AgentWaiting.UNPROVEN_AFTER);

        final AgentWaiting.Derived derived = attached(waiting);
        assertThat(derived.unproven()).isTrue();
        assertThat(derived.screen()).as("still a reading of the screen, not a guess").isEqualTo(AgentWaiting.Screen.NOT_WAITING);
    }

    @Test
    void oneMatchIsEnoughToProveTheDeclaration() {
        final AgentWaiting waiting = declared();
        runner.answering("capture-pane", "Trust this folder? [y/N]\n");
        attached(waiting);
        runner.answering("capture-pane", "stub: at work, nothing asked\n");

        clock.move(AgentWaiting.UNPROVEN_AFTER);

        assertThat(attached(waiting).unproven()).isFalse();
    }

    // --- the last message

    @Test
    void theLastMessageOfARunIsReadFromItsRecordsAndAskedIsCannotSay() throws IOException {
        Files.writeString(dir.resolve("task.log"), """
                {"type":"system","session_id":"s-1"}
                not a record
                {"type":"assistant","message":"thinking"}
                {"type":"result","result":"Which branch should I use?"}
                """);

        final AgentWaiting.Derived derived = declared().about(TASK, false, "asker", "UNATTENDED", dir);

        assertThat(derived.lastMessage()).isEqualTo("Which branch should I use?");
        // No agent can say yet that it stopped to ask: three values, and this is the honest one.
        assertThat(derived.asked()).isEqualTo(AgentWaiting.Asked.CANNOT_SAY);
        assertThat(derived.askedFrom()).isEmpty();
    }

    @Test
    void theNextRunReplacesTheLastMessage() throws IOException {
        final AgentWaiting waiting = declared();
        final Path log = dir.resolve("task.log");
        Files.writeString(log, "{\"type\":\"result\",\"result\":\"Which branch?\"}\n");
        assertThat(waiting.about(TASK, false, "asker", "UNATTENDED", dir).lastMessage()).isEqualTo("Which branch?");

        Files.writeString(log, "{\"type\":\"result\",\"result\":\"Done on main.\"}\n");
        Files.setLastModifiedTime(log, java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(5)));

        assertThat(waiting.about(TASK, false, "asker", "UNATTENDED", dir).lastMessage()).isEqualTo("Done on main.");
    }

    @Test
    void anAgentThatDoesNotSayWhereItsLastMessageIsHasNone() throws IOException {
        Files.writeString(dir.resolve("task.log"), "{\"type\":\"result\",\"result\":\"hello\"}\n");

        assertThat(declared().about(TASK, false, "quiet", "UNATTENDED", dir).lastMessage()).isEmpty();
    }

    /** A clock a test moves. */
    private static final class MovingClock extends Clock {

        private Instant now = Instant.parse("2026-09-29T08:00:00Z");

        void move(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
