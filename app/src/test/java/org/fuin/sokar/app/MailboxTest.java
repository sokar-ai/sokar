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
    void nothing_but_the_box_is_open_and_the_mailbox_around_it_shuts_every_other_account_out() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("sokar-p-t"));

        mailbox.create();

        for (final Path directory : new Path[] {mailbox.root(), mailbox.incoming(), mailbox.accepted(),
                mailbox.rejected(), mailbox.error(), mailbox.record(), mailbox.hold(), mailbox.inbound()}) {
            assertThat(mode(directory)).as(directory.toString()).isEqualTo("rwx------");
        }
    }

    /**
     * Found in walk 9 on 2026-10-04: the agent could not read its own mailbox. The account running Sokar is root in
     * the container, and the agent is another user there; every directory was the account's alone.
     */
    @Test
    void the_agent_reads_its_inbox_moves_what_it_read_and_writes_its_outbox() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("sokar-p-t"));

        mailbox.create();

        for (final Path directory : new Path[] {mailbox.box(), mailbox.box().resolve("inbox"),
                mailbox.box().resolve("outbox"), mailbox.inboxTmp(), mailbox.sentCopies()}) {
            assertThat(mode(directory)).as("passed and read, not written: " + directory).isEqualTo("rwxr-xr-x");
        }
        for (final Path directory : new Path[] {mailbox.inboxNew(), mailbox.inboxCur(), mailbox.outboxTmp(),
                mailbox.outboxNew()}) {
            // Not sticky: the agent renames what the host put into inbox/new, and the host what the agent wrote.
            assertThat(mode(directory)).as("written by the agent: " + directory).isEqualTo("rwxrwxrwx");
        }
    }

    @Test
    void what_the_host_puts_into_the_inbox_the_agent_can_read() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("sokar-p-t"));
        mailbox.create();
        final Path staged = mailbox.inboxTmp().resolve("m-1.json");
        Files.writeString(staged, "{}");
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"));

        mailbox.intoInbox(staged);

        assertThat(staged).doesNotExist();
        assertThat(mode(mailbox.inboxNew().resolve("m-1.json"))).isEqualTo("rw-r--r--");
    }

    @Test
    void creating_it_again_opens_the_box_of_a_task_made_before() throws IOException {
        final Mailbox mailbox = new Mailbox(temp.resolve("sokar-p-t"));
        mailbox.create();
        Files.setPosixFilePermissions(mailbox.outboxNew(), PosixFilePermissions.fromString("rwx------"));

        mailbox.create();

        assertThat(mode(mailbox.outboxNew())).isEqualTo("rwxrwxrwx");
    }

    private static String mode(final Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
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
