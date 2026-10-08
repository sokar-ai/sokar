package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ScreenFile}: an agent's screen read from a file the task writes, not from a process in it.
 */
class ScreenFileTest {

    @TempDir
    Path state;

    private static final Instant NOW = Instant.parse("2026-10-07T17:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private void alive(final Instant at) throws Exception {
        final Path alive = ScreenFile.prepare(state).resolve(ScreenFile.ALIVE);
        Files.writeString(alive, "");
        Files.setLastModifiedTime(alive, FileTime.from(at));
    }

    @Test
    void aWriterThatLooksIsReadAndOneThatStoppedIsNot() throws Exception {
        alive(NOW.minusSeconds(1));
        Files.writeString(ScreenFile.directory(state).resolve(ScreenFile.SCREEN), "Trust this folder? [y/N]\n");
        assertThat(ScreenFile.read(state, clock)).isEqualTo("Trust this folder? [y/N]\n");

        Files.delete(ScreenFile.directory(state).resolve(ScreenFile.SCREEN));
        assertThat(ScreenFile.read(state, clock)).as("no session drawn").isEmpty();

        alive(NOW.minus(ScreenFile.FRESH).minusSeconds(1));
        assertThat(ScreenFile.read(state, clock)).as("a writer that stopped: ask the task").isNull();
        assertThat(ScreenFile.read(state.resolve("never-made"), clock)).isNull();
    }

    @Test
    void aLinkWhereTheScreenShouldBeIsNotFollowed() throws Exception {
        alive(NOW);
        final Path elsewhere = Files.writeString(state.resolve("elsewhere.txt"), "a file of the host's\n");
        Files.createSymbolicLink(ScreenFile.directory(state).resolve(ScreenFile.SCREEN), elsewhere);

        assertThat(ScreenFile.read(state, clock)).isEmpty();
    }

    @Test
    void theAgentsDrawingIsCopiedWhenItChangesAndNoSessionIsSaid() throws Exception {

        // Both sides of the task, against a tmux that draws what a file says.
        final Path directory = ScreenFile.prepare(state);
        final Path bin = Files.createDirectories(state.resolve("bin"));
        final Path drawn = state.resolve("drawn.txt");
        final Path agents = state.resolve("agent-screen");
        Files.writeString(bin.resolve("tmux"), "#!/bin/sh\n[ -s " + drawn + " ] || exit 1\ncat " + drawn + "\n");
        bin.resolve("tmux").toFile().setExecutable(true);
        Files.writeString(drawn, "stub: at work, nothing asked\n");
        final Process drawer = start(ScreenFile.drawerScript(agents.toString()), bin);
        final Process writer = start(ScreenFile.writerScript(directory.toString(), agents.toString()), bin);
        try {
            final Path screen = directory.resolve(ScreenFile.SCREEN);
            waitFor(() -> Files.isRegularFile(screen));
            assertThat(Files.readString(screen)).isEqualTo("stub: at work, nothing asked\n");
            assertThat(directory.resolve(ScreenFile.ALIVE)).exists();

            Files.writeString(drawn, "Trust this folder? [y/N]\n");
            waitFor(() -> Files.readString(screen).startsWith("Trust"));

            Files.writeString(drawn, "");
            waitFor(() -> !Files.exists(screen));
        } finally {
            drawer.destroy();
            writer.destroy();
            drawer.waitFor(5, TimeUnit.SECONDS);
            writer.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void whatTheAgentLeavesInsteadOfADrawingIsNotCopiedAndNothingWaits() throws Exception {

        // The agent's side is the agent's: a link to a file of the root's, or a pipe nobody writes, must neither be
        // read through nor hold the copy.
        final Path directory = ScreenFile.prepare(state);
        final Path agents = state.resolve("agent-screen");
        final Path secret = Files.writeString(state.resolve("roots-own.txt"), "not the agent's to read\n");
        Files.createSymbolicLink(agents, secret);
        final Process writer = start(ScreenFile.writerScript(directory.toString(), agents.toString()), null);
        try {
            waitFor(() -> Files.exists(directory.resolve(ScreenFile.ALIVE)));
            Thread.sleep(1500);
            assertThat(directory.resolve(ScreenFile.SCREEN)).as("a link is not followed").doesNotExist();

            Files.delete(agents);
            new ProcessBuilder("mkfifo", agents.toString()).start().waitFor();
            final java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(directory.resolve(ScreenFile.ALIVE));
            waitFor(() -> Files.getLastModifiedTime(directory.resolve(ScreenFile.ALIVE)).compareTo(before) > 0);
            assertThat(directory.resolve(ScreenFile.SCREEN)).as("a pipe holds nothing up").doesNotExist();
        } finally {
            writer.destroy();
            writer.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static Process start(final String script, final Path bin) throws Exception {
        final ProcessBuilder builder = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        if (bin != null) {
            builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        }
        return builder.start();
    }

    @FunctionalInterface
    private interface Check {
        boolean holds() throws Exception;
    }

    private static void waitFor(final Check check) throws Exception {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!check.holds()) {
            assertThat(System.nanoTime()).as("waited 10 s").isLessThan(until);
            Thread.sleep(100);
        }
    }
}
