package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageRelease}.
 */
class MessageReleaseTest {

    private final MessageRelease release = new MessageRelease();

    private Mailbox held(final Path dir, final String name, final String json) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        Files.writeString(mailbox.hold().resolve(name), json);
        Files.writeString(mailbox.hold().resolve(name + ".sig"),
                "-----BEGIN SSH SIGNATURE-----");
        return mailbox;
    }

    @Test
    void a_released_message_goes_back_where_the_filter_left_it(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = held(dir, "m-1.json", "{\"messageId\":\"m-1\"}");

        final MessageRelease.Result result = release.decide(mailbox, "m-1", false);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.RELEASED);
        assertThat(result.id()).isEqualTo("m-1");
        assertThat(mailbox.accepted().resolve("m-1.json")).exists();
        assertThat(mailbox.accepted().resolve("m-1.json.sig")).as("its signature travels with it")
                .exists();
        assertThat(mailbox.hold().resolve("m-1.json")).doesNotExist();
    }

    @Test
    void a_refused_message_is_kept_and_its_sender_told(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = held(dir, "m-2.json", "{\"messageId\":\"m-2\"}");

        final MessageRelease.Result result = release.decide(mailbox, "m-2.json", true);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.REFUSED);
        assertThat(mailbox.rejected().resolve("m-2.json")).exists();
        assertThat(mailbox.feedback()).isNotEmptyDirectory();
        assertThat(new BounceDelivery().deliver(mailbox)).hasSize(1);
        assertThat(Files.list(mailbox.inboxNew()).toList()).hasSize(1);
    }

    @Test
    void says_so_when_nothing_held_is_called_that(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = held(dir, "m-3.json", "{\"messageId\":\"m-3\"}");

        assertThat(release.decide(mailbox, "m-9", false).outcome())
                .isEqualTo(MessageRelease.Outcome.NO_SUCH_MESSAGE);
    }

    @Test
    void asks_which_one_when_two_answer_to_the_same_id(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = held(dir, "m-4.json", "{\"messageId\":\"m-4\"}");
        Files.writeString(mailbox.hold().resolve("m-4-again.json"), "{\"messageId\":\"m-4\"}");

        assertThat(release.decide(mailbox, "m-4", false).outcome())
                .isEqualTo(MessageRelease.Outcome.AMBIGUOUS);
        assertThat(mailbox.accepted()).as("nothing was moved on a guess").isEmptyDirectory();
    }

    @Test
    void a_message_held_on_its_way_in_is_released_to_this_task_never_out(@TempDir final Path dir)
            throws IOException {

        // Found by Agent Matrix: released, it turned outgoing and was held as "may not address" its own task.
        final Mailbox mailbox = held(dir, "m-7.json", "{\"messageId\":\"m-7\",\"metadata\":{\"to\":\"t\"}}");
        new MessageRecord(mailbox).append(MessageRecord.HELD, "m-7.json", "m-7", "", "it is signed by a key no"
                + " peer is allowed to use", MessageRecord.IN);

        final MessageRelease.Result result = release.decide(mailbox, "m-7", false);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.RELEASED);
        assertThat(result.detail()).isEqualTo(MessageRelease.INWARD);
        assertThat(mailbox.inbound().resolve("m-7.json")).exists();
        assertThat(mailbox.inbound().resolve("m-7.json.sig")).exists();
        assertThat(mailbox.accepted().resolve("m-7.json")).as("never out").doesNotExist();
    }
}
