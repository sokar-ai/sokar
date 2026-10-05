package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.project.Egress;
import org.fuin.sokar.core.project.Limits;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessagePass}, whose subject is the order of the steps rather than any one of them.
 */
class MessagePassTest {

    private final SigningKey key = SigningKey.generate("host");

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final Mail mail = new Mail(List.of(
            new Mail.Peer("reviewer", "local:/peer/inbound", "vouched")));

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return mailbox;
    }

    /**
     * The property the order exists for: a machine whose filter is missing sends nothing at all,
     * and the message is still in the mailbox afterwards rather than lost.
     */
    @Test
    void without_a_filter_nothing_is_queued_and_nothing_is_lost(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.outboxNew().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"reviewer\"}}");

        final MessagePass.Report report = new MessagePass(runner, key, null,
                new TransportDirectory(List.of(dir.resolve("none")))).run(mailbox, mail, List.of(), undecided(dir));

        assertThat(report.taken()).containsExactly("m-1.json");
        assertThat(report.filtered().ran()).isFalse();
        assertThat(report.dispatched().queued()).isEmpty();
        assertThat(report.sent()).isEmpty();
        assertThat(mailbox.incoming().resolve("m-1.json")).as("still here, and signed").exists();
        assertThat(mailbox.incoming().resolve("m-1.json.sig")).exists();
        assertThat(runner.invocations()).isEmpty();
    }

    /**
     * Our own filter being broken is no reason to withhold what somebody else already sent.
     */
    @Test
    void what_arrived_is_delivered_even_when_this_machine_cannot_send(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final SigningKey peerKey = SigningKey.generate("peer");
        final String json = "{\"messageId\":\"in-1\"}";
        Files.writeString(mailbox.inbound().resolve("in-1.json"), json);
        Files.writeString(mailbox.inbound().resolve("in-1.json.sig"), SshSignature.sign(peerKey,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));

        final MessagePass.Report report = new MessagePass(runner, key, null,
                new TransportDirectory(List.of(dir.resolve("none")))).run(mailbox, mail,
                        List.of(new MessageDelivery.Peer("reviewer", List.of(peerKey.keyBlob()))), undecided(dir));

        assertThat(report.filtered().ran()).isFalse();
        assertThat(report.delivered().delivered())
                .extracting(MessageDelivery.Delivered::message).containsExactly("in-1.json");
        assertThat(mailbox.inboxNew().resolve("in-1.json")).exists();
    }

    @Test
    void the_filters_answers_reach_the_agent_last(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.feedback().resolve("answer.json"), "{\"parts\":[{\"data\":{}}]}");

        final MessagePass.Report report = new MessagePass(runner, key, null,
                new TransportDirectory(List.of(dir.resolve("none")))).run(mailbox, mail, List.of(), undecided(dir));

        assertThat(report.bounced()).containsExactly("answer.json");
        assertThat(mailbox.inboxNew().resolve("answer.json")).exists();
    }

    /**
     * The filter's exit 2 says some file was not judged, never that {@code accepted/} holds something
     * unchecked: what it accepted before the fault goes out, and what it did not judge stays in
     * {@code incoming/} for the next pass.
     */
    @Test
    void after_a_filter_fault_what_it_accepted_is_sent_and_what_it_did_not_judge_stays(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        // Accepted by this run of the filter before it met the file it could not judge.
        Files.writeString(mailbox.accepted().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"reviewer\"}}");
        Files.writeString(mailbox.accepted().resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        // And the filter's clean receipt for it, as it answers every message it checked.
        Files.writeString(mailbox.feedback().resolve("receipt-m-1.json"), "{\"messageId\":\"r-1\","
                + "\"metadata\":{\"inReplyToMessageId\":\"m-1\"},\"parts\":[{\"data\":{\"wouldReject\":false}}]}");
        Files.writeString(mailbox.outboxNew().resolve("m-2.json"),
                "{\"messageId\":\"m-2\",\"metadata\":{\"to\":\"reviewer\"}}");
        runner.failing("--mail", 2, "m-2.json could not be read");
        final Moderation moderation = new Moderation(dir.resolve("moderation").resolve("p.json"), sending());

        final MessagePass.Report report = new MessagePass(runner, key, dir.resolve("filter"),
                new TransportDirectory(List.of(dir.resolve("none")))).run(mailbox, mail, List.of(), moderation);

        assertThat(report.filtered().exitCode()).isEqualTo(2);
        assertThat(report.dispatched().queued()).as("what the filter passed").containsOnlyKeys("m-1.json");
        assertThat(mailbox.incoming().resolve("m-2.json")).as("not judged, so not sent and not lost").exists();
        assertThat(mailbox.accepted().resolve("m-2.json")).doesNotExist();
    }

    /** A project whose file lets every peer it names be written to unwatched. */
    private Project sending() {
        return new Project("p", "a project whose agents may talk unwatched", SecurityClass.GUARDED,
                "ubuntu:24.04", null, null, Limits.defaults(), Egress.none(), Project.DEFAULT_PACKAGE_SOURCES,
                new Mail(mail.peers(), java.util.Map.of(), false, new Mail.Rules(null, null, Mail.ALLOW)));
    }

    /** A project nobody has decided anything about. */
    private static Moderation undecided(final Path dir) {
        return new Moderation(dir.resolve("moderation").resolve("p.json"));
    }

    @Test
    void this_machines_own_key_is_believed_and_listed_only_once() {
        final MessagePass pass = new MessagePass(runner, key, null, new TransportDirectory(List.of()));

        final List<MessageDelivery.Peer> with = pass.withOwn(List.of());
        assertThat(with).singleElement().satisfies(peer -> {
            assertThat(peer.name()).isEqualTo("host");
            assertThat(peer.keys()).singleElement().isEqualTo(key.keyBlob());
        });
        // An operator who named this machine's key already keeps their name for it.
        final List<MessageDelivery.Peer> named = List.of(new MessageDelivery.Peer("this-box", List.of(key.keyBlob())));
        assertThat(pass.withOwn(named)).isEqualTo(named);
    }
}
