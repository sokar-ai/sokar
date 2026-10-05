package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.project.Mail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MailboxGuide}.
 */
class MailboxGuideTest {

    @Test
    void tellsTheAgentWhatTheHostAndTheFilterEnforce() {
        final String text = MailboxGuide.text();

        assertThat(text).contains(Mailbox.MOUNT + "/inbox/new").contains(Mailbox.MOUNT + "/outbox/tmp")
                .contains(Mailbox.MOUNT + "/outbox/new").contains(Mailbox.CARD)
                .contains("\"role\": \"" + MessageIntake.AGENT + "\"").contains("metadata.to")
                .contains("\"via\": \"room\"").contains(Mail.PEOPLE).contains("messageId")
                // The operator in walk 10: "Seid Ihr alle da?" to the room, and an agent filed it unanswered.
                .contains("names nobody is meant for every agent in the room:").contains("answer it in the room.")
                .contains("A message that names another is not yours to answer");
    }

    @Test
    void isTheSameForEveryTaskSoAProvidersCacheOfThePromptHolds() {

        // Agent Smith, 2026-10-04: the text ends the system prompt, and a prompt is cached by its beginning.
        assertThat(MailboxGuide.text()).isEqualTo(MailboxGuide.text()).doesNotContain("sokar-");
    }

    @Test
    void writesBothFilesIntoTheBoxForTheAgentToReadAndNamesNoAddress(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();

        MailboxGuide.write(mailbox, new Mail(List.of(new Mail.Peer("people", "matrix:", Mail.Peer.EXTERNAL),
                new Mail.Peer("ops", "mail:ops@example.org", Mail.Peer.EXTERNAL),
                new Mail.Peer("api", "matrix:", Mail.Peer.VOUCHED)), Map.of()));

        assertThat(mailbox.guide()).hasContent(MailboxGuide.text());
        assertThat(mailbox.card()).content().contains("\"people\"").contains("\"ops\"").contains("\"api\"")
                .contains("another task of your project").doesNotContain("ops@example.org").doesNotContain("matrix:");
        for (final Path file : List.of(mailbox.guide(), mailbox.card())) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-r--r--");
        }
        assertThat(mailbox.guide().getParent()).isEqualTo(mailbox.box());
    }

    @Test
    void leavesAFileThatDidNotChangeAlone(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        MailboxGuide.write(mailbox, Mail.none());
        final var before = Files.getLastModifiedTime(mailbox.card());
        Files.setLastModifiedTime(mailbox.card(), java.nio.file.attribute.FileTime.fromMillis(1000));

        MailboxGuide.write(mailbox, Mail.none());

        assertThat(Files.getLastModifiedTime(mailbox.card()).toMillis()).isEqualTo(1000);
        assertThat(before).isNotNull();
    }

    @Test
    void theUserDocumentationShowsTheTextAgentsAreGivenWordForWord() throws IOException {

        // What a person reads about it is what the agent is told, so the two cannot drift apart.
        final String documented = Files.readString(Path.of("..", "doc", "messages.md"));

        for (final String line : MailboxGuide.text().split("\n")) {
            assertThat(documented).as("doc/messages.md").contains(line.startsWith("#") ? "#" + line : line);
        }
    }
}
