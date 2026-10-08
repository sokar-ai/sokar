package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClearanceWiringListeningTest {

    @Test
    void returnsOnceTheWatchersSocketIsThere(@TempDir Path dir) throws IOException {
        final Path socket = Files.createFile(dir.resolve("clearance.sock"));

        assertThat(ClearanceWiring.listening(socket, Duration.ofSeconds(5), () -> true)).isTrue();
    }

    @Test
    void givesUpWhenTheWatcherNeverListens(@TempDir Path dir) {
        // Said, not waited for forever: a start that hangs on its watcher is worse than one that warns.
        final long started = System.nanoTime();

        assertThat(ClearanceWiring.listening(dir.resolve("clearance.sock"), Duration.ofMillis(200), () -> true)).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void stopsWaitingForAWatcherThatHasExited(@TempDir Path dir) {
        // One that died will never listen; waiting out the patience for it only delays saying so.
        final long started = System.nanoTime();

        assertThat(ClearanceWiring.listening(dir.resolve("clearance.sock"), Duration.ofSeconds(30), () -> false)).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }
}
