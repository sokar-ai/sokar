package org.fuin.sokar.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for reading a task's log from its end: a client asks for the last lines, not the whole file.
 */
class LogFromItsEndTest {

    @Test
    void theLastLinesStartWhereTheyStartAndNotAtTheFilesBeginning(@TempDir Path dir) throws IOException {

        // The interface closed while following a log of 53 MB: Tail sent it from its first line.
        final Path log = Files.writeString(dir.resolve("task.log"), "one\ntwo\nthree\nfour\n");

        assertThat(SokarDaemon.startOfLast(log, 2)).isEqualTo("one\ntwo\n".length());
        assertThat(SokarDaemon.read(log, SokarDaemon.startOfLast(log, 2)).lines()).containsExactly("three", "four");
        assertThat(SokarDaemon.startOfLast(log, 10)).isZero();
        assertThat(SokarDaemon.startOfLast(log, 0)).isEqualTo(Files.size(log));
    }

    @Test
    void aLastLineWithoutItsEndCountsAndLongLinesAreCrossed(@TempDir Path dir) throws IOException {

        // A line of 85 698 characters was measured in a real log; finding where it starts reads back past several
        // blocks.
        final String longLine = "x".repeat(200_000);
        final Path log = Files.writeString(dir.resolve("task.log"), "first\n" + longLine + "\nlast", StandardCharsets.UTF_8);

        assertThat(SokarDaemon.startOfLast(log, 1)).isEqualTo(("first\n" + longLine + "\n").length());
        assertThat(SokarDaemon.startOfLast(log, 2)).isEqualTo("first\n".length());
    }
}
