package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A message that arrives by polling, through the whole pass.
 * <p>
 * The two halves that only meet here: the host works out which transport fetched a message, and
 * what that transport promised decides whether the message has to prove who owned it.
 */
class PollingArrivalTest {

    private final SigningKey peerKey = SigningKey.generate("bob");

    private final SigningKey hostKey = SigningKey.generate("host");

    private final Mail mail = new Mail(List.of(new Mail.Peer("bob", "spool:bob", "vouched")));

    private final String json = "{\"messageId\":\"m-1\",\"role\":\"ROLE_AGENT\"}";

    /** A transport that fetches one signed message, and optionally attests who owned it. */
    private Path spool(final Path dir, final String owner) throws IOException {
        final Path transports = dir.resolve("transports");
        Files.createDirectories(transports);
        final Path adapter = transports.resolve(TransportDirectory.PREFIX + "spool");
        final String signature = SshSignature.sign(peerKey,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE);
        Files.writeString(dir.resolve("m-1.json"), json);
        Files.writeString(dir.resolve("m-1.json.sig"), signature);
        Files.writeString(adapter, "#!/bin/sh\n"
                + "case \"$1\" in\n"
                + "  describe) echo '{\"scheme\":\"spool\",\"poll\":true,\"attests\":[\"owner\"]}' ;;\n"
                + "  poll) shift; cp " + dir.resolve("m-1.json") + " \"$2/m-1.json\";"
                + " cp " + dir.resolve("m-1.json.sig") + " \"$2/m-1.json.sig\";"
                + (owner == null ? "" : " printf '" + owner + "' > \"$2/m-1.json.owner\";")
                + " echo '{\"received\":1}' ;;\n"
                + "esac\n");
        Files.setPosixFilePermissions(adapter, PosixFilePermissions.fromString("rwx------"));
        return transports;
    }

    private MessagePass.Report run(final Path dir, final String owner) throws IOException {
        final Path transports = spool(dir, owner);
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        // No filter installed, which stops nothing arriving: what a peer sent is delivered whether
        // or not this machine can send anything today.
        final MessagePass pass = new MessagePass(new ProcessCommandRunner(), hostKey, null,
                new TransportDirectory(List.of(transports)));
        return pass.run(mailbox, mail,
                List.of(new MessageDelivery.Peer("bob", List.of(peerKey.keyBlob()))));
    }

    @Test
    void a_polled_message_whose_owner_matches_its_peer_is_delivered(@TempDir final Path dir)
            throws IOException {
        final MessagePass.Report report = run(dir, "1004 bob");

        assertThat(report.polled().arrivals()).containsEntry("m-1.json", "spool");
        assertThat(report.delivered().delivered())
                .extracting(MessageDelivery.Delivered::message).containsExactly("m-1.json");
    }

    /**
     * The promise is the point: a transport that said it attests an owner and handed one over
     * without it has its message held, however good the signature.
     */
    @Test
    void a_polled_message_without_the_promised_owner_is_held(@TempDir final Path dir)
            throws IOException {
        final MessagePass.Report report = run(dir, null);

        assertThat(report.delivered().delivered()).isEmpty();
        assertThat(report.delivered().held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("attests who owned it, and it did not"));
    }

    @Test
    void a_polled_message_owned_by_somebody_else_is_held(@TempDir final Path dir)
            throws IOException {
        final MessagePass.Report report = run(dir, "1009 carol");

        assertThat(report.delivered().delivered()).isEmpty();
        assertThat(report.delivered().held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("written by the user 'carol'"));
    }
}
