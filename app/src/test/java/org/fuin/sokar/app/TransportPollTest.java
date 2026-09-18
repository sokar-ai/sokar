package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link TransportPoll}.
 * <p>
 * Driven with real little programs rather than a fake runner: what is worth asserting is that the
 * host works out <em>which</em> transport put a file there by looking at the directory, and a fake
 * that reported what it did would be testing the claim this exists not to trust.
 */
class TransportPollTest {

    private Path adapter(final Path dir, final String name, final String script)
            throws IOException {
        Files.createDirectories(dir);
        final Path file = dir.resolve(TransportDirectory.PREFIX + name);
        Files.writeString(file, "#!/bin/sh\n" + script);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return mailbox;
    }

    private TransportPoll poll(final Path transports) {
        return new TransportPoll(new org.fuin.sokar.core.process.ProcessCommandRunner(),
                new TransportDirectory(java.util.List.of(transports)));
    }

    @Test
    void asks_a_transport_that_polls_and_learns_what_it_brought(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        adapter(dir.resolve("transports"), "spool", """
                case "$1" in
                  describe) echo '{"scheme":"spool","poll":true,"attests":["owner"]}' ;;
                  poll) shift; printf '{"messageId":"m-1"}' > "$2/m-1.json"; echo '{"received":1}' ;;
                esac
                """);

        final TransportPoll.Outcome outcome = poll(dir.resolve("transports")).poll(mailbox);

        assertThat(outcome.arrivals()).containsEntry("m-1.json", "spool");
        assertThat(outcome.failures()).isEmpty();
        assertThat(mailbox.inbound().resolve("m-1.json")).exists();
    }

    @Test
    void does_not_ask_a_transport_that_says_it_needs_no_polling(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        adapter(dir.resolve("transports"), "local", """
                case "$1" in
                  describe) echo '{"scheme":"local","poll":false,"attests":[]}' ;;
                  poll) printf '{"messageId":"never"}' > "$3/never.json" ;;
                esac
                """);

        final TransportPoll.Outcome outcome = poll(dir.resolve("transports")).poll(mailbox);

        assertThat(outcome.arrivals()).isEmpty();
        assertThat(mailbox.inbound().resolve("never.json")).doesNotExist();
    }

    /**
     * A transport that cannot be asked what it is gets nothing: not polled, and attesting nothing.
     */
    @Test
    void a_transport_that_cannot_describe_itself_is_not_polled(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        adapter(dir.resolve("transports"), "broken", "exit 3\n");

        assertThat(poll(dir.resolve("transports")).poll(mailbox).arrivals()).isEmpty();
    }

    @Test
    void a_transport_that_cannot_run_is_reported_not_swallowed(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        adapter(dir.resolve("transports"), "spool", """
                case "$1" in
                  describe) echo '{"scheme":"spool","poll":true,"attests":["owner"]}' ;;
                  poll) echo 'no drop directory on this machine' >&2; exit 2 ;;
                esac
                """);

        final TransportPoll.Outcome outcome = poll(dir.resolve("transports")).poll(mailbox);

        assertThat(outcome.failures()).hasEntrySatisfying("spool",
                why -> assertThat(why).contains("cannot run").contains("no drop directory"));
    }

    /**
     * What it fetched before failing is still there, so it still counts - anything else would
     * leave messages nobody looks at again.
     */
    @Test
    void what_arrived_before_a_failure_still_counts(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        adapter(dir.resolve("transports"), "spool", """
                case "$1" in
                  describe) echo '{"scheme":"spool","poll":true,"attests":["owner"]}' ;;
                  poll) shift; printf '{"messageId":"m-1"}' > "$2/m-1.json"; exit 2 ;;
                esac
                """);

        final TransportPoll.Outcome outcome = poll(dir.resolve("transports")).poll(mailbox);

        assertThat(outcome.arrivals()).containsEntry("m-1.json", "spool");
        assertThat(outcome.failures()).containsKey("spool");
    }

    /**
     * The reason the host looks rather than asks: two transports, and each message belongs to
     * exactly the one that put it there.
     */
    @Test
    void tells_two_transports_arrivals_apart(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final Path transports = dir.resolve("transports");
        adapter(transports, "aaa", """
                case "$1" in
                  describe) echo '{"scheme":"aaa","poll":true,"attests":[]}' ;;
                  poll) shift; printf 'x' > "$2/from-aaa.json" ;;
                esac
                """);
        adapter(transports, "bbb", """
                case "$1" in
                  describe) echo '{"scheme":"bbb","poll":true,"attests":["owner"]}' ;;
                  poll) shift; printf 'x' > "$2/from-bbb.json" ;;
                esac
                """);

        final TransportPoll.Outcome outcome = poll(transports).poll(mailbox);

        assertThat(outcome.arrivals())
                .containsEntry("from-aaa.json", "aaa")
                .containsEntry("from-bbb.json", "bbb");
    }
}
