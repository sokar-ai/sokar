package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.wire.TaskMode;
import org.fuin.sokar.wire.TaskProfile;
import org.fuin.sokar.agent.api.Waiting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link TaskInventory#tasks()}: a listing of many attached agents reads their screens several at once.
 */
class TaskListingScreensTest {

    @Test
    void aListingOfTwelveAttachedAgentsTakesAboutTheTimeOfAFewScreenReadsNotTwelve(@TempDir final Path dir)
            throws Exception {

        // 'List' took 1.2 s at 10 tasks and 3.8 s at 30, a screen read of about 100 ms each in
        // turn.
        final StringBuilder listed = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            final String container = "sokar-p-t" + i;
            listed.append(container).append("\tUp 4 minutes\n");
            final Path state = Files.createDirectories(dir.resolve("run/sokar").resolve(container));
            new TaskProfile(TaskProfile.VERSION, "asker", TaskMode.AGENT, null, "sokar/work", "2026-10-07T12:00:00Z",
                    null).writeTo(state);
        }
        final AtomicInteger reads = new AtomicInteger();
        final CommandRunner runner = command -> {
            final String said = command.toString();
            if (said.contains("capture-pane")) {
                reads.incrementAndGet();
                try {
                    Thread.sleep(200);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new CommandResult(command, 0, "stub: at work, nothing asked\n", "");
            }
            return new CommandResult(command, 0, said.contains(" ps") ? listed.toString() : "", "");
        };
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        final Waiting declared = new Waiting(java.util.List.of(new Waiting.Rule("Trust this folder?", 3, "trust")),
                java.util.List.of(), null);
        final TaskInventory inventory = new TaskInventory(context, new AgentWaiting(context,
                java.time.Clock.systemUTC(), () -> Map.of("asker", Optional.of(declared))));

        final long started = System.nanoTime();
        final var tasks = inventory.tasks();
        final Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(tasks).hasSize(12);
        assertThat(reads.get()).as("each screen read once").isEqualTo(12);
        assertThat(took).as("12 reads of 200 ms, eight at a time").isLessThan(Duration.ofMillis(1200));
    }
}
