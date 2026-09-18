package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.project.Egress;
import org.fuin.sokar.core.project.Limits;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageDispatch}.
 */
class MessageDispatchTest {

    private final Mail mail = new Mail(List.of(
            new Mail.Peer("reviewer", "local:/somewhere/mail/sokar-p-r/inbound", "vouched"),
            new Mail.Peer("ops", "mail:ops@example.org", "external")));

    private Project sending() {
        return new Project("p", "a project whose agents may talk unwatched", SecurityClass.GUARDED,
                "ubuntu:24.04", null, null, Limits.defaults(), Egress.none(),
                Project.DEFAULT_PACKAGE_SOURCES, Mail.none(), true);
    }

    /** Sending at all needs a person's decision, so every queueing test starts by making one. */
    private Moderation allowing(final Mailbox mailbox, final String peer) throws IOException {
        final Moderation moderation = new Moderation(mailbox);
        assertThat(moderation.set(peer, null, "allow", sending()).refused()).isEmpty();
        return moderation;
    }

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

        final MessageDispatch.Outcome outcome =
                new MessageDispatch().dispatch(mailbox, mail, allowing(mailbox, "reviewer"));

        assertThat(outcome.queued()).containsEntry("m-1.json", "local");
        assertThat(mailbox.queueActive("local").resolve("m-1.json")).exists();
        assertThat(mailbox.queueActive("local").resolve("m-1.json.sig")).exists();
        assertThat(mailbox.queueActive("local").resolve("m-1.json.to"))
                .hasContent("/somewhere/mail/sokar-p-r/inbound");
        assertThat(mailbox.accepted()).isEmptyDirectory();
    }

    /**
     * The default, and the largest thing a person controls: nothing they have not read leaves.
     */
    @Test
    void holds_a_message_for_a_peer_nobody_has_decided_about(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = accepted(dir, "m-5.json",
                "{\"messageId\":\"m-5\",\"metadata\":{\"to\":\"reviewer\"}}");

        final MessageDispatch.Outcome outcome = new MessageDispatch().dispatch(mailbox, mail);

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("waits for a person"));
        assertThat(mailbox.hold().resolve("m-5.json")).exists();
    }

    @Test
    void holds_everything_for_a_peer_a_person_held(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = accepted(dir, "m-6.json",
                "{\"messageId\":\"m-6\",\"metadata\":{\"to\":\"reviewer\"}}");
        final Moderation moderation = allowing(mailbox, "reviewer");
        moderation.set("reviewer", true, null, sending());

        final MessageDispatch.Outcome outcome =
                new MessageDispatch().dispatch(mailbox, mail, moderation);

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("held until a person releases it"));
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
