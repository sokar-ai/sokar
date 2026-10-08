package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageFile}.
 */
class MessageFileTest {

    @Test
    void aFileThatIsNoMessageClaimsNoRoleAndStopsNothing(@TempDir final Path dir) throws IOException {

        // Found by the suite on 2026-10-04: an agent wrote a line of text into its outbox, and every pass of its mailbox
        // stopped there with "expected 'false' at offset 0". It is the filter's to refuse, and it answers the sender.
        final Path file = dir.resolve("m-agent.json");
        Files.writeString(file, "from-the-agent\n");

        assertThat(MessageFile.role(file)).isEmpty();
    }

    @Test
    void aMessageClaimsTheRoleItNames(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("m-1.json");
        Files.writeString(file, "{\"messageId\":\"m-1\",\"role\":\"ROLE_AGENT\"}");

        assertThat(MessageFile.role(file)).isEqualTo("ROLE_AGENT");
    }
}
