package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageIntake}.
 */
class MessageIntakeTest {

    private final SigningKey key = SigningKey.generate("host");

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return mailbox;
    }

    @Test
    void takes_a_finished_message_and_signs_the_bytes_as_they_are(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final String json = "{\"messageId\":\"m-1\"}";
        Files.writeString(mailbox.outboxNew().resolve("m-1.json"), json);

        final List<String> taken = new MessageIntake(key).take(mailbox);

        assertThat(taken).containsExactly("m-1.json");
        assertThat(mailbox.outboxNew().resolve("m-1.json")).doesNotExist();
        assertThat(mailbox.incoming().resolve("m-1.json")).exists();
        final String signature =
                Files.readString(mailbox.incoming().resolve("m-1.json.sig"));
        assertThat(SshSignature.verify(json.getBytes(StandardCharsets.UTF_8), signature,
                key.keyBlob(), MessageIntake.NAMESPACE)).isTrue();
    }

    @Test
    void leaves_what_the_agent_is_still_writing(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.outboxTmp().resolve("half.json"), "{\"messageId\"");

        assertThat(new MessageIntake(key).take(mailbox)).isEmpty();
        assertThat(mailbox.outboxTmp().resolve("half.json")).exists();
    }

    @Test
    void ignores_what_is_not_a_message(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.outboxNew().resolve(".keep"), "");
        Files.writeString(mailbox.outboxNew().resolve("notes.txt"), "hello");

        assertThat(new MessageIntake(key).take(mailbox)).isEmpty();
        assertThat(mailbox.outboxNew().resolve("notes.txt")).exists();
    }

    /**
     * The signature has to be there before the message is, because a message with no signature
     * cannot be told from one whose signature was never written.
     */
    @Test
    void a_message_never_arrives_without_its_signature(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        for (int i = 0; i < 5; i++) {
            Files.writeString(mailbox.outboxNew().resolve("m-" + i + ".json"),
                    "{\"messageId\":\"m-" + i + "\"}");
        }

        new MessageIntake(key).take(mailbox);

        try (var entries = Files.list(mailbox.incoming())) {
            entries.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(message -> assertThat(
                            message.resolveSibling(message.getFileName() + ".sig")).exists());
        }
    }

    @Test
    void whatIsTakenIsTheHostsOwnCopyWhichTheAgentCannotChangeAfterwards(@TempDir final Path dir) throws IOException {

        // Renamed onward, the file stayed the agent's inode: through a hard link it kept in its outbox it could change
        // a message after it was signed, after the filter and after a person read it.
        final Mailbox mailbox = mailbox(dir);
        final Path message = mailbox.outboxNew().resolve("m-1.json");
        Files.writeString(message, "{\"messageId\":\"m-1\",\"role\":\"ROLE_AGENT\"}");
        final Path kept = mailbox.outboxTmp().resolve("kept");
        Files.createLink(kept, message);

        new MessageIntake(key).take(mailbox);
        Files.writeString(kept, "{\"messageId\":\"m-1\",\"role\":\"ROLE_USER\"}");

        assertThat(mailbox.incoming().resolve("m-1.json")).content().contains("ROLE_AGENT");
    }

    @Test
    void aLinkTheAgentPlacedIsNotFollowed(@TempDir final Path dir) throws IOException {

        // Followed, the host read whatever file its account can and signed it as the agent's message.
        final Mailbox mailbox = mailbox(dir);
        final Path secret = Files.writeString(dir.resolve("secret.json"), "{\"messageId\":\"s\",\"key\":\"x\"}");
        Files.createSymbolicLink(mailbox.outboxNew().resolve("m-1.json"), secret);

        assertThat(new MessageIntake(key).take(mailbox)).isEmpty();
        assertThat(mailbox.incoming().resolve("m-1.json")).doesNotExist();
    }

    @Test
    void aFileBeyondTheLimitIsNotReadAndStopsNothing(@TempDir final Path dir) throws IOException {

        // Read whole, a sparse file of gigabytes threw OutOfMemoryError and ended messaging on the machine.
        final Mailbox mailbox = mailbox(dir);
        try (java.io.RandomAccessFile huge = new java.io.RandomAccessFile(
                mailbox.outboxNew().resolve("a.json").toFile(), "rw")) {
            huge.setLength(3L * 1024 * 1024 * 1024);
        }
        Files.writeString(mailbox.outboxNew().resolve("b.json"), "{\"messageId\":\"b\"}");

        assertThat(new MessageIntake(key).take(mailbox)).containsExactly("b.json");
        assertThat(mailbox.outboxNew().resolve("a.json")).doesNotExist();
    }

    @Test
    void anOutboxTheAgentReplacedWithALinkIsNotReadAndWhatItPointsAtIsLeftAlone(@TempDir final Path dir)
            throws IOException {

        // The box is the task's: replaced by a link, its outbox led the host to read, sign and delete files anywhere
        // its account can.
        final Mailbox mailbox = mailbox(dir);
        final Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("precious.json"), "{\"messageId\":\"p\"}");
        Files.delete(mailbox.outboxNew());
        Files.createSymbolicLink(mailbox.outboxNew(), elsewhere);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MessageIntake(key).take(mailbox))
                .isInstanceOf(IOException.class).hasMessageContaining("link");
        assertThat(elsewhere.resolve("precious.json")).exists();
    }
}
