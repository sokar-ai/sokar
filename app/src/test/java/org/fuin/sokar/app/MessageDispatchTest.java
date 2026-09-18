package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.project.Mail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageDispatch}.
 */
class MessageDispatchTest {

    private final Mail mail = new Mail(List.of(
            new Mail.Peer("reviewer", "local:/somewhere/mail/sokar-p-r/inbound", "vouched"),
            new Mail.Peer("ops", "mail:ops@example.org", "external")));

    private Mailbox accepted(final Path dir, final String name, final String json)
            throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        Files.writeString(mailbox.accepted().resolve(name), json);
        Files.writeString(mailbox.accepted().resolve(name + ".sig"), "-----BEGIN SSH SIGNATURE-----");
        return mailbox;
    }

    @Test
    void queues_a_message_for_the_transport_its_peer_uses(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = accepted(dir, "m-1.json",
                "{\"messageId\":\"m-1\",\"metadata\":{\"kind\":\"question\",\"to\":\"reviewer\"}}");

        final MessageDispatch.Outcome outcome = new MessageDispatch().dispatch(mailbox, mail);

        assertThat(outcome.queued()).containsEntry("m-1.json", "local");
        assertThat(mailbox.queueActive("local").resolve("m-1.json")).exists();
        assertThat(mailbox.queueActive("local").resolve("m-1.json.sig")).exists();
        assertThat(mailbox.queueActive("local").resolve("m-1.json.to"))
                .hasContent("/somewhere/mail/sokar-p-r/inbound");
        assertThat(mailbox.accepted()).isEmptyDirectory();
    }

    @Test
    void holds_a_message_for_a_peer_this_project_may_not_address(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = accepted(dir, "m-2.json",
                "{\"messageId\":\"m-2\",\"metadata\":{\"to\":\"somebody-else\"}}");

        final MessageDispatch.Outcome outcome = new MessageDispatch().dispatch(mailbox, mail);

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("may not address 'somebody-else'"));
        assertThat(mailbox.hold().resolve("m-2.json")).exists();
    }

    @Test
    void holds_a_message_that_addresses_nobody(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = accepted(dir, "m-3.json", "{\"messageId\":\"m-3\"}");

        assertThat(new MessageDispatch().dispatch(mailbox, mail).held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("addresses nobody"));
    }

    /**
     * Sending to several peers is a copy and a receipt per copy. Refusing it in words beats
     * delivering to the first name and saying nothing about the rest.
     */
    @Test
    void refuses_to_guess_when_a_message_addresses_two_peers(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = accepted(dir, "m-4.json",
                "{\"messageId\":\"m-4\",\"metadata\":{\"to\":[\"reviewer\",\"ops\"]}}");

        assertThat(new MessageDispatch().dispatch(mailbox, mail).held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("not built"));
        assertThat(mailbox.queueActive("local")).doesNotExist();
    }
}
