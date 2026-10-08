package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link BounceDelivery}.
 */
class BounceDeliveryTest {

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return mailbox;
    }

    @Test
    void an_answer_arrives_where_the_agent_already_reads(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.feedback().resolve("20260918T0713Z--msg-a.json"),
                "{\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"refused\"},{\"data\":{}}]}");

        final var delivered = new BounceDelivery().deliver(mailbox);

        assertThat(delivered).containsExactly("20260918T0713Z--msg-a.json");
        assertThat(mailbox.inboxNew().resolve("20260918T0713Z--msg-a.json")).exists();
        assertThat(mailbox.feedback()).isEmptyDirectory();
        assertThat(mailbox.inboxTmp()).isEmptyDirectory();
    }

    /**
     * A bounce carries a data part, which the narrowed schema refuses for anything arriving from
     * outside. It is delivered without a signature and without being filtered because it never left
     * this machine - and the check that would refuse it is the one that wrote it.
     */
    @Test
    void a_data_part_is_not_a_reason_to_refuse_the_filters_own_answer(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.feedback().resolve("answer.json"),
                "{\"parts\":[{\"data\":{\"decision\":\"rejected\"}}]}");

        assertThat(new BounceDelivery().deliver(mailbox)).containsExactly("answer.json");
        assertThat(mailbox.inboxNew().resolve("answer.json")).exists();
        assertThat(mailbox.hold()).isEmptyDirectory();
    }

    @Test
    void nothing_to_answer_is_not_an_error(@TempDir final Path dir) throws IOException {
        assertThat(new BounceDelivery().deliver(mailbox(dir))).isEmpty();
    }
}
