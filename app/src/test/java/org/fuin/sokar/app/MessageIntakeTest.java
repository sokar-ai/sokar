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
}
