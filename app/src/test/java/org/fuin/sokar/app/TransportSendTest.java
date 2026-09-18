package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link TransportSend}, whose whole contract is three exit codes.
 */
class TransportSendTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private TransportDirectory withAdapter(final Path dir) throws IOException {
        final Path transports = dir.resolve("transports");
        Files.createDirectories(transports);
        final Path adapter = transports.resolve(TransportDirectory.PREFIX + "local");
        Files.writeString(adapter, "#!/bin/sh\n");
        Files.setPosixFilePermissions(adapter, PosixFilePermissions.fromString("rwx------"));
        return new TransportDirectory(List.of(transports));
    }

    private Mailbox queued(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        final Path active = mailbox.queueActive("local");
        Files.createDirectories(active);
        Files.writeString(active.resolve("m-1.json"), "{\"messageId\":\"m-1\"}");
        Files.writeString(active.resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        Files.writeString(active.resolve("m-1.json.to"), "/peer/inbound");
        return mailbox;
    }

    @Test
    void a_message_the_transport_took_moves_to_sent_with_its_receipt(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = queued(dir);
        runner.answering("send", "{\"delivered\":true}");

        final TransportSend.Result result =
                new TransportSend(runner, withAdapter(dir)).send(mailbox, "local");

        assertThat(result.sent()).containsExactly("m-1.json");
        assertThat(mailbox.sent().resolve("m-1.json")).exists();
        assertThat(mailbox.sent().resolve("m-1.json.sig")).as("the evidence travels with it").exists();
        assertThat(mailbox.sent().resolve("m-1.json.receipt.json")).hasContent("{\"delivered\":true}");
        assertThat(mailbox.queueActive("local")).isEmptyDirectory();
    }

    @Test
    void a_temporary_failure_waits_and_is_tried_again(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = queued(dir);
        final TransportDirectory transports = withAdapter(dir);
        runner.failing("send", TransportSend.TEMPORARY, "the mailbox is not writable yet");

        final TransportSend.Result first = new TransportSend(runner, transports).send(mailbox, "local");

        assertThat(first.deferred()).containsExactly("m-1.json");
        assertThat(mailbox.queueDeferred("local").resolve("m-1.json")).exists();

        // The next pass finds it again, and this time the far side is there.
        final FakeCommandRunner later = new FakeCommandRunner().answering("send", "{}");
        final TransportSend.Result second = new TransportSend(later, transports).send(mailbox, "local");

        assertThat(second.sent()).containsExactly("m-1.json");
        assertThat(mailbox.queueDeferred("local")).isEmptyDirectory();
    }

    @Test
    void a_permanent_failure_is_not_retried(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = queued(dir);
        runner.failing("send", 1, "no such destination");

        final TransportSend.Result result =
                new TransportSend(runner, withAdapter(dir)).send(mailbox, "local");

        assertThat(result.refused()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("no such destination"));
        assertThat(mailbox.hold().resolve("m-1.json")).exists();
        assertThat(mailbox.queueActive("local")).isEmptyDirectory();
        assertThat(mailbox.queueDeferred("local")).doesNotExist();
    }

    /**
     * A machine without the adapter has lost nothing. The message waits, and installing the
     * transport later is what makes the queue move - it is not a refusal, and the sender is not
     * told the peer is gone.
     */
    @Test
    void a_transport_that_is_not_installed_leaves_the_message_waiting(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = queued(dir);

        final TransportSend.Result result = new TransportSend(runner,
                new TransportDirectory(List.of(dir.resolve("none")))).send(mailbox, "local");

        assertThat(result.deferred()).containsExactly("m-1.json");
        assertThat(result.refused()).isEmpty();
        assertThat(mailbox.queueDeferred("local").resolve("m-1.json")).exists();
        assertThat(runner.invocations()).as("nothing was run").isEmpty();
    }
}
