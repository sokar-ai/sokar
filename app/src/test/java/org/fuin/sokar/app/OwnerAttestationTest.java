package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link OwnerAttestation}: the second fact about a message, which the signature cannot
 * give and the host cannot recover once the message has been moved.
 */
class OwnerAttestationTest {

    private final Mail mail = new Mail(List.of(
            new Mail.Peer("bob", "spool:bob", "vouched"),
            new Mail.Peer("reviewer", "local:/peer/inbound", "vouched")));

    private Mailbox arrived(final Path dir, final String name) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        Files.writeString(mailbox.inbound().resolve(name), "{\"messageId\":\"m-1\"}");
        return mailbox;
    }

    private void attest(final Mailbox mailbox, final String name, final String said)
            throws IOException {
        Files.writeString(mailbox.inbound().resolve(name + OwnerAttestation.SUFFIX), said,
                StandardCharsets.UTF_8);
    }

    @Test
    void a_message_owned_by_the_user_its_peer_is_registered_as_goes_on(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");
        attest(mailbox, "m-1.json", "1004 bob\n");

        assertThat(OwnerAttestation.refuse(mailbox, mailbox.inbound().resolve("m-1.json"), "bob",
                mail, true)).isEmpty();
    }

    /**
     * The case the whole attestation exists for: Carol copies a validly signed message of Bob's
     * into Alice's drop. The signature still verifies; the file is Carol's.
     */
    @Test
    void a_valid_signature_written_by_somebody_else_is_held(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");
        attest(mailbox, "m-1.json", "1009 carol");

        assertThat(OwnerAttestation.refuse(mailbox, mailbox.inbound().resolve("m-1.json"), "bob",
                mail, true))
                .contains("written by the user 'carol'").contains("registered as the user 'bob'");
    }

    @Test
    void a_transport_that_promised_and_did_not_attest_is_held(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");

        assertThat(OwnerAttestation.refuse(mailbox, mailbox.inbound().resolve("m-1.json"), "bob",
                mail, true)).contains("it attests who owned it, and it did not");
    }

    /**
     * A transport that promised nothing cannot lose a message by not writing a file it never
     * claimed to write.
     */
    @Test
    void a_transport_that_promised_nothing_owes_nothing(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");

        assertThat(OwnerAttestation.refuse(mailbox, mailbox.inbound().resolve("m-1.json"),
                "reviewer", mail, false)).isEmpty();
    }

    /**
     * But an attestation that is there is read whoever left it, because reading it can only hold a
     * message and never admit one. Found on the VM: a message fetched in an earlier pass reached
     * an agent with its owner file lying unread beside it.
     */
    @Test
    void an_attestation_that_is_there_is_read_even_unpromised(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");
        attest(mailbox, "m-1.json", "1009 carol");

        assertThat(OwnerAttestation.refuse(mailbox, mailbox.inbound().resolve("m-1.json"), "bob",
                mail, false)).contains("written by the user 'carol'");
    }

    @Test
    void an_attestation_nobody_can_read_is_held(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");
        attest(mailbox, "m-1.json", "bob");

        assertThat(OwnerAttestation.refuse(mailbox, mailbox.inbound().resolve("m-1.json"), "bob",
                mail, true)).contains("cannot be read");
    }

    @Test
    void a_peer_the_project_does_not_list_has_nothing_to_compare_against(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");
        attest(mailbox, "m-1.json", "1004 bob");

        assertThat(OwnerAttestation.refuse(mailbox, mailbox.inbound().resolve("m-1.json"),
                "a-stranger", mail, true)).contains("does not list");
    }

    /** And through delivery, where it has to stop a message that is otherwise perfect. */
    @Test
    void delivery_holds_what_the_owner_contradicts(@TempDir final Path dir) throws IOException {
        final SigningKey bobs = SigningKey.generate("bob");
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        final String json = "{\"messageId\":\"m-1\"}";
        Files.writeString(mailbox.inbound().resolve("m-1.json"), json);
        Files.writeString(mailbox.inbound().resolve("m-1.json.sig"), SshSignature.sign(bobs,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
        attest(mailbox, "m-1.json", "1009 carol");

        final MessageDelivery.Outcome outcome = new MessageDelivery().deliver(mailbox,
                List.of(new MessageDelivery.Peer("bob", List.of(bobs.keyBlob()))), Set.of(), null,
                null, (box, message, peer) ->
                        OwnerAttestation.refuse(box, message, peer, mail, true));

        assertThat(outcome.delivered()).isEmpty();
        assertThat(outcome.held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("written by the user 'carol'"));
        assertThat(mailbox.inboxNew()).isEmptyDirectory();
    }

    /** Nothing is left in the inbound afterwards: the attestation travels with its message. */
    @Test
    void the_attestation_travels_with_the_message(@TempDir final Path dir) throws IOException {
        final SigningKey bobs = SigningKey.generate("bob");
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        final String json = "{\"messageId\":\"m-1\"}";
        Files.writeString(mailbox.inbound().resolve("m-1.json"), json);
        Files.writeString(mailbox.inbound().resolve("m-1.json.sig"), SshSignature.sign(bobs,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
        attest(mailbox, "m-1.json", "1004 bob");

        new MessageDelivery().deliver(mailbox,
                List.of(new MessageDelivery.Peer("bob", List.of(bobs.keyBlob()))), Set.of(), null,
                null, (box, message, peer) -> OwnerAttestation.refuse(box, message, peer, mail,
                        true));

        assertThat(mailbox.inbound().resolve("m-1.json.owner")).doesNotExist();
        assertThat(mailbox.record().resolve("m-1.json.owner")).as("kept as evidence").exists();
    }

    /** And a held one takes it along, so an operator can read why it was held. */
    @Test
    void a_held_message_keeps_its_attestation_beside_it(@TempDir final Path dir)
            throws IOException {
        final SigningKey bobs = SigningKey.generate("bob");
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        final String json = "{\"messageId\":\"m-1\"}";
        Files.writeString(mailbox.inbound().resolve("m-1.json"), json);
        Files.writeString(mailbox.inbound().resolve("m-1.json.sig"), SshSignature.sign(bobs,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
        attest(mailbox, "m-1.json", "1009 carol");

        new MessageDelivery().deliver(mailbox,
                List.of(new MessageDelivery.Peer("bob", List.of(bobs.keyBlob()))), Set.of(), null,
                null, (box, message, peer) -> OwnerAttestation.refuse(box, message, peer, mail,
                        true));

        assertThat(mailbox.hold().resolve("m-1.json.owner")).exists();
        assertThat(mailbox.inbound().resolve("m-1.json.owner")).doesNotExist();
    }
}
