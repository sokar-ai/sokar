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
 * Test for {@link MessageBudget}.
 */
class MessageBudgetTest {

    private final Mail mail = new Mail(List.of(
            new Mail.Peer("reviewer", "local:/peer/inbound", "vouched", 2)));

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return mailbox;
    }

    @Test
    void a_quiet_conversation_is_within_budget(@TempDir final Path dir) throws IOException {
        final MessageBudget budget =
                new MessageBudget(new MessageRecord(mailbox(dir)), mail);

        assertThat(budget.outbound("reviewer")).isEmpty();
        assertThat(budget.inbound("reviewer")).isEmpty();
    }

    @Test
    void counts_each_direction_on_its_own(@TempDir final Path dir) throws IOException {
        final MessageRecord record = new MessageRecord(mailbox(dir));
        record.append(MessageRecord.QUEUED, "m-1.json", "m-1", "reviewer", "local");
        record.append(MessageRecord.QUEUED, "m-2.json", "m-2", "reviewer", "local");
        final MessageBudget budget = new MessageBudget(record, mail);

        assertThat(budget.outbound("reviewer")).contains("2 message(s)");
        assertThat(budget.inbound("reviewer")).as("what was sent is not what was received")
                .isEmpty();
    }

    @Test
    void a_peer_that_talks_too_much_is_refused_on_the_way_in(@TempDir final Path dir)
            throws IOException {
        final MessageRecord record = new MessageRecord(mailbox(dir));
        record.append(MessageRecord.DELIVERED, "m-1.json", "m-1", "reviewer", "");
        record.append(MessageRecord.DELIVERED, "m-2.json", "m-2", "reviewer", "");

        assertThat(new MessageBudget(record, mail).inbound("reviewer"))
                .contains("accepted from reviewer");
    }

    @Test
    void a_peer_the_project_does_not_list_is_not_this_steps_refusal(@TempDir final Path dir)
            throws IOException {
        assertThat(new MessageBudget(new MessageRecord(mailbox(dir)), mail).outbound("stranger"))
                .isEmpty();
    }

    /**
     * Rolling, and read from the record itself: the count is what happened, not a counter that
     * could disagree with it.
     */
    @Test
    void yesterdays_messages_do_not_count(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(MessageRecord.QUEUED, "m-1.json", "m-1", "reviewer", "local");
        record.append(MessageRecord.QUEUED, "m-2.json", "m-2", "reviewer", "local");
        final Path log = mailbox.record().resolve(MessageRecord.FILE);
        Files.writeString(log, Files.readString(log).replaceAll(
                "\"at\":\"20[0-9-]{8}", "\"at\":\"2020-01-01"));

        assertThat(new MessageBudget(record, mail).outbound("reviewer")).isEmpty();
    }
}
