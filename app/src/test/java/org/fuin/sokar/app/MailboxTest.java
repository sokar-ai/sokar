package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link Mailbox}.
 */
class MailboxTest {

    @TempDir
    private Path temp;

    @Test
    void creates_every_directory_a_task_needs() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("sokar-p-t"));

        mailbox.create();

        assertThat(mailbox.inboxTmp()).isDirectory();
        assertThat(mailbox.inboxNew()).isDirectory();
        assertThat(mailbox.inboxCur()).isDirectory();
        assertThat(mailbox.outboxTmp()).isDirectory();
        assertThat(mailbox.outboxNew()).isDirectory();
        assertThat(mailbox.sentCopies()).isDirectory();
        assertThat(mailbox.incoming()).isDirectory();
        assertThat(mailbox.accepted()).isDirectory();
        assertThat(mailbox.feedback()).isDirectory();
        assertThat(mailbox.rejected()).isDirectory();
        assertThat(mailbox.error()).isDirectory();
        assertThat(mailbox.index()).isDirectory();
        assertThat(mailbox.hold()).isDirectory();
        assertThat(mailbox.inbound()).isDirectory();
        assertThat(mailbox.inboundTmp()).isDirectory();
        assertThat(mailbox.sent()).isDirectory();
        assertThat(mailbox.record()).isDirectory();
    }

    @Test
    void nothing_in_it_is_readable_by_another_account() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("sokar-p-t"));

        mailbox.create();

        for (final Path directory : new Path[] {mailbox.root(), mailbox.box(), mailbox.inboxNew(),
                mailbox.outboxNew(), mailbox.rejected(), mailbox.error(), mailbox.record()}) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
                    .as(directory.toString()).isEqualTo("rwx------");
        }
    }

    /**
     * The mount hands the agent its own inbox and outbox. Anything else in the mailbox - the
     * refused originals above all - has to stay on the host's side of it, so this asserts on
     * where the directories are rather than on how the mount is spelt somewhere else.
     */
    @Test
    void what_the_container_sees_holds_no_refusal_and_no_record() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("sokar-p-t"));

        mailbox.create();

        assertThat(mailbox.inboxNew()).startsWith(mailbox.box());
        assertThat(mailbox.outboxNew()).startsWith(mailbox.box());
        assertThat(mailbox.sentCopies()).startsWith(mailbox.box());
        assertThat(mailbox.rejected().startsWith(mailbox.box())).isFalse();
        assertThat(mailbox.error().startsWith(mailbox.box())).isFalse();
        assertThat(mailbox.record().startsWith(mailbox.box())).isFalse();
        assertThat(mailbox.incoming().startsWith(mailbox.box())).isFalse();
        assertThat(mailbox.hold().startsWith(mailbox.box())).isFalse();
    }

    @Test
    void creating_it_twice_keeps_what_is_in_it() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("sokar-p-t"));
        mailbox.create();
        final Path message = mailbox.inboxNew().resolve("one.json");
        Files.writeString(message, "{}");

        mailbox.create();

        assertThat(message).exists();
    }

    @Test
    void deleting_it_takes_everything_with_it() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("sokar-p-t"));
        mailbox.create();
        Files.writeString(mailbox.rejected().resolve("secret.json"), "{}");

        mailbox.delete();

        assertThat(mailbox.exists()).isFalse();
        assertThat(mailbox.root()).doesNotExist();
    }

    @Test
    void deleting_a_mailbox_that_was_never_made_is_not_an_error() throws IOException {
        new Mailbox(temp.resolve("never")).delete();
    }
}
