package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for how {@link TaskLogsCommand} follows a growing log.
 */
class TaskLogsCommandTest {

    private final StringWriter written = new StringWriter();

    private final PrintWriter out = new PrintWriter(written);

    @Test
    void printsOnlyWhatArrivedSinceTheLastRead(@TempDir Path dir) throws IOException {

        // By byte position rather than line count: a log grows between two reads, and counting
        // lines would reprint whatever arrived in between.
        final Path log = dir.resolve("gate.log");
        Files.writeString(log, "first\n");
        final long after = TaskLogsCommand.print(log, 0, out);
        out.flush();
        assertThat(written.toString()).isEqualTo("first\n");

        Files.writeString(log, "first\nsecond\n");
        TaskLogsCommand.print(log, after, out);
        out.flush();
        assertThat(written.toString()).isEqualTo("first\nsecond\n");
    }

    @Test
    void startsAgainWhenTheLogGotShorter(@TempDir Path dir) throws IOException {

        // Something truncated or replaced it. Seeking to the old position would be past the end,
        // and the follow would print nothing for ever while the file filled up again.
        final Path log = dir.resolve("gate.log");
        Files.writeString(log, "a long first run of output\n");
        final long after = TaskLogsCommand.print(log, 0, out);
        written.getBuffer().setLength(0);

        Files.writeString(log, "new\n");
        final long position = TaskLogsCommand.print(log, after, out);
        out.flush();

        assertThat(written.toString()).isEqualTo("new\n");
        assertThat(position).isEqualTo(Files.size(log));
    }

    @Test
    void saysNothingAboutALogThatHasNotChanged(@TempDir Path dir) throws IOException {

        final Path log = dir.resolve("gate.log");
        Files.writeString(log, "a line\n");
        final long after = TaskLogsCommand.print(log, 0, out);
        written.getBuffer().setLength(0);

        assertThat(TaskLogsCommand.print(log, after, out)).isEqualTo(after);
        out.flush();
        assertThat(written.toString()).isEmpty();
    }

    @Test
    void waitsRatherThanFailingForALogThatIsNotThereYet(@TempDir Path dir) throws IOException {

        // A helper writes its log when it starts, which may be after somebody began following.
        assertThat(TaskLogsCommand.print(dir.resolve("not-yet.log"), 0, out)).isZero();
    }
}
