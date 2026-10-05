package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageDelivery}.
 */
class MessageDeliveryTest {

    private final SigningKey peerKey = SigningKey.generate("peer");

    private final MessageDelivery delivery = new MessageDelivery();

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return mailbox;
    }

    private List<MessageDelivery.Peer> reviewer() {
        return List.of(new MessageDelivery.Peer("reviewer", List.of(peerKey.keyBlob())));
    }

    private void arrive(final Mailbox mailbox, final String name, final String json,
            final String signature) throws IOException {
        Files.writeString(mailbox.inbound().resolve(name), json);
        if (signature != null) {
            Files.writeString(mailbox.inbound().resolve(name + ".sig"), signature);
        }
    }

    @Test
    void delivers_what_a_known_peer_signed(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final String json = "{\"messageId\":\"m-1\"}";
        arrive(mailbox, "m-1.json", json, SshSignature.sign(peerKey,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));

        final MessageDelivery.Outcome outcome = delivery.deliver(mailbox, reviewer());

        assertThat(outcome.delivered()).extracting(MessageDelivery.Delivered::message)
                .containsExactly("m-1.json");
        assertThat(outcome.held()).isEmpty();
        assertThat(mailbox.inboxNew().resolve("m-1.json")).exists();
        assertThat(mailbox.inboxTmp()).isEmptyDirectory();
        assertThat(mailbox.record().resolve("m-1.json.sig")).as("evidence stays on the host")
                .exists();
    }

    @Test
    void holds_what_arrived_without_a_signature(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        arrive(mailbox, "m-2.json", "{\"messageId\":\"m-2\"}", null);

        final MessageDelivery.Outcome outcome = delivery.deliver(mailbox, reviewer());

        assertThat(outcome.delivered()).isEmpty();
        assertThat(outcome.held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("without a signature"));
        assertThat(mailbox.inboxNew()).isEmptyDirectory();
        assertThat(mailbox.hold().resolve("m-2.json")).as("kept, because somebody tried").exists();
    }

    @Test
    void holds_what_a_key_no_peer_may_use_signed(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final String json = "{\"messageId\":\"m-3\"}";
        final SigningKey stranger = SigningKey.generate("stranger");
        arrive(mailbox, "m-3.json", json, SshSignature.sign(stranger,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));

        final MessageDelivery.Outcome outcome = delivery.deliver(mailbox, reviewer());

        assertThat(outcome.delivered()).isEmpty();
        assertThat(outcome.held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("no peer is allowed"));
        assertThat(mailbox.inboxNew()).isEmptyDirectory();
    }

    /**
     * The case the signature exists for: the bytes changed after they were signed.
     */
    @Test
    void holds_a_message_that_changed_after_it_was_signed(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final String signed = "{\"messageId\":\"m-4\"}";
        final String arrivedAs = "{\"messageId\":\"m-4 \"}";
        arrive(mailbox, "m-4.json", arrivedAs, SshSignature.sign(peerKey,
                signed.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));

        final MessageDelivery.Outcome outcome = delivery.deliver(mailbox, reviewer());

        assertThat(outcome.delivered()).isEmpty();
        assertThat(outcome.held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("does not match"));
        assertThat(mailbox.inboxNew()).isEmptyDirectory();
    }

    /**
     * A transport that could not confirm a hand-over and tried again must not make the agent read
     * the same message twice.
     */
    @Test
    void drops_a_message_whose_id_was_delivered_before(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final String json = "{\"messageId\":\"m-6\"}";
        arrive(mailbox, "m-6.json", json, SshSignature.sign(peerKey,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));

        final MessageDelivery.Outcome outcome =
                delivery.deliver(mailbox, reviewer(), Set.of("m-6"));

        assertThat(outcome.delivered()).isEmpty();
        assertThat(outcome.held()).isEmpty();
        assertThat(outcome.duplicates()).singleElement()
                .satisfies(repeat -> assertThat(repeat.id()).isEqualTo("m-6"));
        assertThat(mailbox.inboxNew()).isEmptyDirectory();
        assertThat(mailbox.inbound().resolve("m-6.json")).as("nothing is left to arrive again")
                .doesNotExist();
        assertThat(mailbox.inbound().resolve("m-6.json.sig")).doesNotExist();
    }

    @Test
    void delivers_the_same_id_only_once_within_one_pass(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final String json = "{\"messageId\":\"m-7\"}";
        final String signature = SshSignature.sign(peerKey,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE);
        arrive(mailbox, "m-7.json", json, signature);
        arrive(mailbox, "m-7-again.json", json, signature);

        final MessageDelivery.Outcome outcome = delivery.deliver(mailbox, reviewer());

        assertThat(outcome.delivered()).extracting(MessageDelivery.Delivered::message)
                .containsExactly("m-7-again.json");
        assertThat(outcome.duplicates()).extracting(MessageDelivery.Repeat::message)
                .containsExactly("m-7.json");
    }

    @Test
    void stops_at_the_days_budget_within_one_pass(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        for (final String id : List.of("m-8", "m-9")) {
            final String json = "{\"messageId\":\"" + id + "\"}";
            arrive(mailbox, id + ".json", json, SshSignature.sign(peerKey,
                    json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
        }
        final MessageBudget budget = new MessageBudget(new MessageRecord(mailbox),
                new org.fuin.sokar.core.project.Mail(List.of(
                        new org.fuin.sokar.core.project.Mail.Peer("reviewer",
                                "local:/peer/inbound", "vouched", 1))));

        final MessageDelivery.Outcome outcome =
                delivery.deliver(mailbox, reviewer(), Set.of(), null, budget);

        assertThat(outcome.delivered()).hasSize(1);
        assertThat(outcome.held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("accepted from reviewer"));
    }

    @Test
    void what_is_not_a_signature_is_held_rather_than_throwing(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        arrive(mailbox, "m-5.json", "{\"messageId\":\"m-5\"}", "not a signature at all");

        final MessageDelivery.Outcome outcome = delivery.deliver(mailbox, reviewer());

        assertThat(outcome.held()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("cannot be read"));
    }

    @Test
    void aSignedMessageThatIsNoJsonIsHeldAndTheNextOneIsStillDelivered(@TempDir final Path dir) throws IOException {

        // Its id could not be read, the pass threw, and the file stayed in inbound: the task never received anything
        // again.
        final Mailbox mailbox = mailbox(dir);
        arrive(mailbox, "a.json", "not json", SshSignature.sign(peerKey,
                "not json".getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
        final String json = "{\"messageId\":\"b\"}";
        arrive(mailbox, "b.json", json, SshSignature.sign(peerKey, json.getBytes(StandardCharsets.UTF_8),
                MessageIntake.NAMESPACE));

        final MessageDelivery.Outcome outcome = delivery.deliver(mailbox, reviewer());

        assertThat(outcome.held()).extracting(MessageDelivery.Held::message).containsExactly("a.json");
        assertThat(outcome.delivered()).extracting(MessageDelivery.Delivered::message).containsExactly("b.json");
    }
}
