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
    void a_fresh_account_whose_first_mailbox_comes_after_the_start_is_watched_too(@TempDir final Path dir)
            throws IOException {
        // No mail/ when the daemon started: the watch gave up, and every message waited for the timed pass, 37-60 s.
        final SokarContext context = context(dir);
        try (MessageWatch watch = new MessageWatch(context, Duration.ZERO)) {
            assertThat(watch.startNotices()).as("nothing to watch yet is no reason to stop watching").isTrue();
            final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox("sokar-p-t"));
            mailbox.create();
            Files.writeString(mailbox.inbound().resolve("m-4.json"), "{\"messageId\":\"m-4\"}");

            assertThat(appearsWithin(mailbox.hold().resolve("m-4.json"), Duration.ofSeconds(5)))
                    .as("noticed in a mailbox made after the start").isTrue();
        }
    }

    @Test
    void a_conversations_answer_is_one_line_in_the_journal() {
        // The key holds a line break, so the timing landed on a line of its own that no search for 'messages' found.
        assertThat(MessageWatch.answered(TransportConversations.key("matrix", "sluicetest"), 35, 1))
                .isEqualTo("messages  matrix sluicetest answered after 35 ms with 1 message(s)");
    }

    @Test
    void a_persons_message_written_on_the_host_is_noticed(@TempDir final Path dir) throws IOException {
        // 'sokar talk say' writes into person/, which was not watched: such a message went with the next pass of any
        // other cause, up to the timed one a minute on.
        final SokarContext context = context(dir);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox("sokar-p-t"));
        mailbox.create();
        try (MessageWatch watch = new MessageWatch(context, Duration.ZERO)) {
            assertThat(watch.startNotices()).isTrue();
            Files.writeString(mailbox.person().resolve("m-5.json"), "{\"metadata\":{\"to\":\"nobody\"}}");

            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (Files.exists(mailbox.person().resolve("m-5.json")) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            assertThat(mailbox.person().resolve("m-5.json")).as("taken on its notice").doesNotExist();
        }
    }
}
