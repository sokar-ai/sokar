package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageRecord}.
 */
class MessageRecordTest {

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return mailbox;
    }

    @Test
    void an_empty_record_holds_together(@TempDir final Path dir) throws IOException {
        assertThat(new MessageRecord(mailbox(dir)).firstBrokenLine()).isZero();
    }

    @Test
    void remembers_what_was_delivered(@TempDir final Path dir) throws IOException {
        final MessageRecord record = new MessageRecord(mailbox(dir));

        record.append(MessageRecord.TAKEN, "m-1.json", "", "");
        record.append(MessageRecord.DELIVERED, "m-2.json", "m-2", "");
        record.append(MessageRecord.HELD, "m-3.json", "", "it arrived without a signature");

        assertThat(record.delivered()).containsExactly("m-2");
        assertThat(record.size()).isEqualTo(3);
        assertThat(record.firstBrokenLine()).isZero();
    }

    @Test
    void holds_no_message_text(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final MessageRecord record = new MessageRecord(mailbox);

        record.append(MessageRecord.HELD, "m-1.json", "m-1", "the filter refused it");

        assertThat(Files.readString(mailbox.record().resolve(MessageRecord.FILE)))
                .as("a record that quoted refused messages would copy what was refused")
                .doesNotContain("the secret").contains("the filter refused it");
    }

    /**
     * The point of chaining: a line changed after the fact is named, not silently accepted.
     */
    @Test
    void names_the_first_line_that_was_changed_afterwards(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(MessageRecord.TAKEN, "m-1.json", "", "");
        record.append(MessageRecord.HELD, "m-2.json", "", "the filter refused it");
        record.append(MessageRecord.DELIVERED, "m-3.json", "m-3", "");

        final Path file = mailbox.record().resolve(MessageRecord.FILE);
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.set(1, lines.get(1).replace("the filter refused it", "nothing happened"));
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertThat(record.firstBrokenLine()).isEqualTo(2);
    }

    @Test
    void names_a_line_that_was_removed(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(MessageRecord.TAKEN, "m-1.json", "", "");
        record.append(MessageRecord.HELD, "m-2.json", "", "the filter refused it");
        record.append(MessageRecord.DELIVERED, "m-3.json", "m-3", "");

        final Path file = mailbox.record().resolve(MessageRecord.FILE);
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Files.write(file, List.of(lines.get(0), lines.get(2)), StandardCharsets.UTF_8);

        assertThat(record.firstBrokenLine()).as("the line after the gap no longer follows")
                .isEqualTo(2);
    }
}
