package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for the half of {@link MessageWatch} that does not wait for the timer.
 */
class MessageWatchNoticeTest {

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    /**
     * The case this exists for: both halves of a conversation on one machine, where waiting a
     * minute for an answer already lying in a directory is a delay with no cause.
     */
    @Test
    void a_message_that_lands_is_noticed_without_the_timer(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox("sokar-p-t"));
        mailbox.create();
        Files.writeString(mailbox.inbound().resolve("m-1.json"), "{\"messageId\":\"m-1\"}");

        // Off, so nothing but the watch can move anything.
        try (MessageWatch watch = new MessageWatch(context, Duration.ZERO)) {
            assertThat(watch.startNotices()).isTrue();
            Files.writeString(mailbox.inbound().resolve("m-2.json"), "{\"messageId\":\"m-2\"}");

            // Unsigned, so the pass holds them - which is exactly the evidence that a pass ran.
            assertThat(appears(mailbox.hold().resolve("m-2.json")))
                    .as("it was noticed, with the timer off").isTrue();
        }
    }

    @Test
    void a_message_that_lands_moves_while_a_conversation_step_runs(@TempDir final Path dir) throws Exception {
        // Every pass asked each conversation's transport first, under the one lock a notice waited for too: a written
        // message took 4-8 s to be taken, and a file a long-poll handed in 5-20 s to be delivered.
        final SokarContext context = context(dir);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox("sokar-p-t"));
        mailbox.create();
        try (MessageWatch watch = new MessageWatch(context, Duration.ZERO)) {
            assertThat(watch.startNotices()).isTrue();
            final java.lang.reflect.Field passing = MessageWatch.class.getDeclaredField("passing");
            passing.setAccessible(true);
            final Object lock = passing.get(watch);
            final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            final Thread step = Thread.ofVirtual().start(() -> {
                synchronized (lock) {
                    held.countDown();
                    try {
                        release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertThat(held.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                Files.writeString(mailbox.inbound().resolve("m-3.json"), "{\"messageId\":\"m-3\"}");

                assertThat(appearsWithin(mailbox.hold().resolve("m-3.json"), Duration.ofSeconds(3)))
                        .as("moved while the conversation step still runs").isTrue();
            } finally {
                release.countDown();
                step.join();
            }
        }
    }

    /** Waits for something another thread does, without holding the build up when it does not. */
    private boolean appears(final Path file) {
        return appearsWithin(file, Duration.ofSeconds(20));
    }

    private boolean appearsWithin(final Path file, final Duration patience) {
        final long deadline = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Test
    void a_machine_with_no_mailboxes_yet_does_not_refuse_to_watch(@TempDir final Path dir) {
        final SokarContext context = context(dir);
        try (MessageWatch watch = new MessageWatch(context, Duration.ZERO)) {
            assertThat(watch.startNotices()).as("nothing to watch is not a failure to watch")
                    .isFalse();
        }
    }
}
