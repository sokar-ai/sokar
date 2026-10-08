package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.sokar.agent.api.AgentEnd;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AgentEnding}: a task whose agent ended says so, with the error and whose it was.
 */
class AgentEndingTest {

    private static final Instant NOW = Instant.parse("2026-10-03T13:30:00Z");

    @Test
    void anAgentThatEndedWithAProvidersRefusalIsSaidSoWithTheTextAndWhatWasLeftUnpushed(@TempDir Path dir)
            throws IOException {

        // The provider answered 402 and the agent ended; the task said 'idle', then 'working', and nothing pointed the
        // person at what had happened, nor at the report it had written and never pushed.
        final Path log = Files.writeString(dir.resolve("task.log"),
                "{\"type\":\"start\"}\n{\"type\":\"agent_end\",\"error\":\"402 more credits\"}\n\n");
        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(120)));
        final FakeCommandRunner runner = new FakeCommandRunner().answering("git status", "1 0");
        final AgentEnding ending = new AgentEnding(runner, Clock.fixed(NOW, ZoneOffset.UTC), (agent, lines) ->
                lines.stream().anyMatch(line -> line.contains("agent_end"))
                        ? new AgentEnd(false, "402 more credits", AgentEnd.PROVIDER, 402) : null);

        final AgentEnding.Ended ended = ending.about("sokar-default-contracts", true, "example", dir);

        assertThat(ended).isNotNull();
        assertThat(ended.finished()).isFalse();
        assertThat(ended.error()).isEqualTo("402 more credits");
        assertThat(ended.source()).isEqualTo("provider");
        assertThat(ended.status()).isEqualTo(402);
        assertThat(ended.unpushed()).isEqualTo(1);
        assertThat(ended.at()).isEqualTo(NOW.minusSeconds(120).toString());
    }

    @Test
    void aLogStillGrowingIsNotAskedAndAnUnchangedOneIsNotAskedTwice(@TempDir Path dir) throws IOException {

        // Asked on every pass, every running task would start every agent twice a second.
        final Path log = Files.writeString(dir.resolve("task.log"), "{\"type\":\"agent_end\"}\n");
        final AtomicInteger asked = new AtomicInteger();
        final AgentEnding ending = new AgentEnding(new FakeCommandRunner().answering("git status", "0 0"),
                Clock.fixed(NOW, ZoneOffset.UTC), (agent, lines) -> {
                    asked.incrementAndGet();
                    return new AgentEnd(true, "", AgentEnd.AGENT, null);
                });

        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(2)));
        assertThat(ending.about("sokar-p-t", true, "example", dir)).as("still being written").isNull();
        assertThat(asked).hasValue(0);

        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(60)));
        assertThat(ending.about("sokar-p-t", true, "example", dir)).isNotNull();
        assertThat(ending.about("sokar-p-t", true, "example", dir)).isNotNull();
        assertThat(asked).hasValue(1);
    }

    @Test
    void aTaskWithoutALogOrAnAgentThatSaysNothingHasNoEnd(@TempDir Path dir) throws IOException {
        final AgentEnding ending = new AgentEnding(new FakeCommandRunner(), Clock.fixed(NOW, ZoneOffset.UTC),
                (agent, lines) -> null);
        assertThat(ending.about("sokar-p-t", true, "example", dir)).isNull();

        final Path log = Files.writeString(dir.resolve("task.log"), "working\n");
        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(60)));
        assertThat(ending.about("sokar-p-t", true, "example", dir)).isNull();
        assertThat(ending.about("sokar-p-t", true, null, dir)).isNull();
    }

    @Test
    void aTaskWhoseContainerStoppedWhenItsAgentEndedStillSaysHowAndWhatItLeftUnpushed(@TempDir Path dir)
            throws IOException {

        // Measured with a second agent on the VM: its container stopped with its run, and the task said only 'dead'.
        final Path log = Files.writeString(dir.resolve("task.log"), "{\"type\":\"agent_end\"}\n");
        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(120)));
        UnhandedWork.note(dir, UnhandedWork.census("2 1"));
        final AgentEnding ending = new AgentEnding(new FakeCommandRunner(), Clock.fixed(NOW, ZoneOffset.UTC),
                (agent, lines) -> new AgentEnd(false, "401 Missing Authentication header", AgentEnd.PROVIDER, 401));

        final AgentEnding.Ended ended = ending.about("sokar-refused-talk", false, "example", dir);

        assertThat(ended).isNotNull();
        assertThat(ended.status()).isEqualTo(401);
        assertThat(ended.unpushed()).as("as recorded when it stopped").isEqualTo(3);
    }
}
