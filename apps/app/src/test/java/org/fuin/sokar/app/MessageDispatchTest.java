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

    /** A project whose file gives every peer it names this mode. */
    private Project sending(final String mode) {
        return new Project("p", "a project whose agents may talk unwatched", SecurityClass.GUARDED,
                "ubuntu:24.04", null, null, Limits.defaults(), Egress.none(), Project.DEFAULT_PACKAGE_SOURCES,
                new Mail(mail.peers(), java.util.Map.of(), false, new Mail.Rules(null, null, mode)));
    }

    /** Sending at all needs the project's file to allow it, so every queueing test starts with one that does. */
    private Moderation allowing(final Mailbox mailbox) {
        return new Moderation(mailbox.root().resolveSibling("moderation-p.json"), sending(Mail.ALLOW));
    }

    private Mailbox accepted(final Path dir, final String name, final String json)
            throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        Files.writeString(mailbox.accepted().resolve(name), json);
        Files.writeString(mailbox.accepted().resolve(name + ".sig"), "-----BEGIN SSH SIGNATURE-----");
        // The filter's clean receipt, as it answers every message it checked.
        final java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"messageId\":\"([^\"]+)\"").matcher(json);
        if (id.find()) {
            Files.writeString(mailbox.feedback().resolve("receipt-" + name), "{\"messageId\":\"r-" + id.group(1)
                    + "\",\"metadata\":{\"inReplyToMessageId\":\"" + id.group(1) + "\"},\"parts\":[{\"data\":"
                    + "{\"decision\":\"approved\",\"wouldReject\":false}}]}");
        }
        return mailbox;
    }

    @Test
    void queues_a_message_for_the_transport_its_peer_uses(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = accepted(dir, "m-1.json",
                "{\"messageId\":\"m-1\",\"metadata\":{\"kind\":\"question\",\"to\":\"reviewer\"}}");

        final MessageDispatch.Outcome outcome =
                new MessageDispatch().dispatch(mailbox, mail, allowing(mailbox));

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

        final MessageDispatch.Outcome outcome = new MessageDispatch().dispatch(mailbox, mail, undecided(mailbox));

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("waits for a person"));
        assertThat(mailbox.hold().resolve("m-5.json")).exists();
    }

    @Test
    void holds_everything_for_a_peer_a_person_held(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = accepted(dir, "m-6.json",
                "{\"messageId\":\"m-6\",\"metadata\":{\"to\":\"reviewer\"}}");
        final Moderation moderation = allowing(mailbox);
        moderation.set("reviewer", true, null, sending(Mail.ALLOW));

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

        final MessageDispatch.Outcome outcome = new MessageDispatch().dispatch(mailbox, mail, undecided(mailbox));

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("may not address 'somebody-else'"));
        assertThat(mailbox.hold().resolve("m-2.json")).exists();
    }

    @Test
    void holds_a_message_that_addresses_nobody(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = accepted(dir, "m-3.json", "{\"messageId\":\"m-3\"}");

        assertThat(new MessageDispatch().dispatch(mailbox, mail, undecided(mailbox)).held()).singleElement()
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

        assertThat(new MessageDispatch().dispatch(mailbox, mail, undecided(mailbox)).held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("not built"));
        assertThat(mailbox.queueActive("local")).doesNotExist();
    }

    /** The project's decisions, beside the mailbox and outside it, as the host keeps them. */
    @Test
    void a_message_a_person_released_goes_although_the_peer_is_undecided_but_never_to_a_refused_peer(
            @TempDir final Path dir) throws IOException {

        // Found: released, then held again by the same mode at the next pass, for ever.
        final Mailbox mailbox = accepted(dir, "m-9.json",
                "{\"messageId\":\"m-9\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(mailbox.accepted().resolve("m-9.json" + MessageDispatch.RELEASED_SUFFIX), "");

        final MessageDispatch.Outcome outcome = new MessageDispatch().dispatch(mailbox, mail, undecided(mailbox));

        assertThat(outcome.queued()).containsEntry("m-9.json", "local");
        assertThat(mailbox.accepted()).as("the mark goes with the release").isEmptyDirectory();

        final Mailbox refusing = accepted(dir.resolve("other"), "m-10.json",
                "{\"messageId\":\"m-10\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(refusing.accepted().resolve("m-10.json" + MessageDispatch.RELEASED_SUFFIX), "");
        final Moderation denying = new Moderation(refusing.root().resolveSibling("moderation-p.json"),
                sending(Mail.DENY));
        assertThat(new MessageDispatch().dispatch(refusing, mail, denying).queued()).isEmpty();
    }

    /** The operator in walk 10, 2026-10-04: a release is needed only when the filter flags something as a problem. */
    @Test
    void aMessageTheReportingFilterWouldHaveRefusedWaitsForAPersonWhateverItsPeersMode(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = accepted(dir, "m-7.json",
                "{\"messageId\":\"m-7\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(mailbox.feedback().resolve("answer-m-7.json"), "{\"messageId\":\"a-7\","
                + "\"metadata\":{\"inReplyToMessageId\":\"m-7\"},\"parts\":[{\"text\":\"approved\"},"
                + "{\"data\":{\"decision\":\"approved\",\"wouldReject\":true,\"decidingRule\":\"ENCODING/HEX\","
                + "\"findings\":[{\"redactedExcerpt\":\"2c8a****\"}]}}]}");

        final MessageDispatch.Outcome outcome = new MessageDispatch().dispatch(mailbox, mail, allowing(mailbox));

        assertThat(outcome.queued()).isEmpty();
        assertThat(outcome.held()).singleElement().satisfies(held -> assertThat(held.reason())
                .contains("the filter would have refused it: ENCODING/HEX"));
    }

    @Test
    void aMessageAPersonReleasedGoesThoughTheReportingFilterFlaggedIt(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = accepted(dir, "m-8.json",
                "{\"messageId\":\"m-8\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(mailbox.feedback().resolve("answer-m-8.json"), "{\"messageId\":\"a-8\","
                + "\"metadata\":{\"inReplyToMessageId\":\"m-8\"},\"parts\":[{\"data\":{\"wouldReject\":true}}]}");
        Files.writeString(mailbox.accepted().resolve("m-8.json" + MessageDispatch.RELEASED_SUFFIX), "");

        assertThat(new MessageDispatch().dispatch(mailbox, mail, allowing(mailbox)).queued()).containsKey("m-8.json");
    }

    @Test
    void aMessageWhoseAnswerFromTheFilterIsMissingWaitsForAPerson(@TempDir final Path dir) throws IOException {

        // Fail closed (2026-10-04): a lost answer must never read as clean.
        final Mailbox mailbox = accepted(dir, "m-11.json", "{\"messageId\":\"m-11\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.delete(mailbox.feedback().resolve("receipt-m-11.json"));

        assertThat(new MessageDispatch().dispatch(mailbox, mail, allowing(mailbox)).held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("answer to it is missing"));
    }

    @Test
    void anAnswerTheAgentRemovedFromItsBoxStillHoldsWhatTheFilterFlagged(@TempDir final Path dir) throws IOException {

        // 2026-10-04: the agent can empty its own inbox, so only the host's copy is asked.
        final Mailbox mailbox = accepted(dir, "m-12.json", "{\"messageId\":\"m-12\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(mailbox.feedback().resolve("receipt-m-12.json"), "{\"messageId\":\"r-12\","
                + "\"metadata\":{\"inReplyToMessageId\":\"m-12\"},\"parts\":[{\"data\":{\"wouldReject\":true}}]}");
        new BounceDelivery().deliver(mailbox);
        try (var delivered = Files.list(mailbox.inboxNew())) {
            for (final Path each : delivered.toList()) {
                Files.delete(each);
            }
        }

        assertThat(new MessageDispatch().dispatch(mailbox, mail, allowing(mailbox)).held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("would have refused"));
    }

    @Test
    void aMessageWhoseIdCannotBeReadWaitsForAPerson(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = accepted(dir, "m-13.json", "{\"metadata\":{\"to\":\"reviewer\"}}");

        assertThat(new MessageDispatch().dispatch(mailbox, mail, allowing(mailbox)).held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("id cannot be read"));
    }

    private static Moderation undecided(final Mailbox mailbox) {
        return new Moderation(mailbox.root().resolveSibling("moderation-p.json"));
    }
}
