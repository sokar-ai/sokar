package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageRead}.
 */
class MessageReadTest {

    private static final String MESSAGE = """
            {"messageId":"m-1","role":"ROLE_AGENT","contextId":"c-1",
             "parts":[{"text":"Can you review PR 12?","mediaType":"text/plain"},{"mediaType":"image/png","data":"iVBOR"}],
             "metadata":{"kind":"review-request","to":"reviewer"}}
            """;

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return mailbox;
    }

    @Test
    void showsAHeldMessagesTextKindPeerAndWhyItIsHeld(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.hold().resolve("m-1.json"), MESSAGE);
        new MessageRecord(mailbox).append(MessageRecord.HELD, "m-1.json", "m-1", "reviewer",
                "held for a person: the mode for reviewer is prompt");

        final MessageRead.Held held = new MessageRead().read(mailbox, "m-1");

        assertThat(held.outcome()).isEqualTo(MessageRead.Outcome.FOUND);
        assertThat(held.message()).isEqualTo("m-1.json");
        assertThat(held.role()).isEqualTo("ROLE_AGENT");
        assertThat(held.kind()).isEqualTo("review-request");
        assertThat(held.peer()).isEqualTo("reviewer");
        assertThat(held.reason()).isEqualTo("held for a person: the mode for reviewer is prompt");
        assertThat(held.at()).as("when this host first recorded it").isNotEmpty();
        // A part that is not text is named, never passed through as bytes.
        assertThat(held.text()).containsExactly("Can you review PR 12?", "[image/png]");
    }

    @Test
    void listsEverythingAPersonMayBeAskedAboutWithWhereItStandsAndNeverWhatItSays(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.hold().resolve("m-1.json"), MESSAGE);
        new MessageRecord(mailbox).append(MessageRecord.HELD, "m-1.json", "m-1", "reviewer",
                "held for a person: the mode for reviewer is prompt");
        Files.createDirectories(mailbox.rejected());
        Files.writeString(mailbox.rejected().resolve("m-2.json"), MESSAGE.replace("m-1", "m-2"));
        Files.createDirectories(mailbox.error());
        Files.write(mailbox.error().resolve("broken.json"), new byte[] { (byte) 0xff, 'x' });

        final java.util.List<MessageRead.Held> listed = new MessageRead().list(mailbox);

        assertThat(listed).extracting(MessageRead.Held::message, MessageRead.Held::standing).containsExactly(
                org.assertj.core.groups.Tuple.tuple("m-1.json", "held"),
                org.assertj.core.groups.Tuple.tuple("m-2.json", "refused-by-filter"),
                org.assertj.core.groups.Tuple.tuple("broken.json", "unchecked"));
        assertThat(listed.get(0).peer()).isEqualTo("reviewer");
        assertThat(listed.get(0).reason()).isEqualTo("held for a person: the mode for reviewer is prompt");
        // A list that quoted a refused message would be a second copy of what was refused.
        assertThat(listed).allSatisfy(held -> assertThat(held.text()).isEmpty());
        // Each is exactly what a read of it finds, text aside.
        final MessageRead.Held read = new MessageRead().read(mailbox, "m-2");
        assertThat(listed.get(1)).isEqualTo(new MessageRead.Held(read.outcome(), read.standing(), read.message(),
                read.id(), read.role(), read.peer(), read.kind(), read.at(), java.util.List.of(), read.reason(),
                read.direction()));
    }

    @Test
    void saysWhichWayEachMessageWasGoing(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final MessageRecord record = new MessageRecord(mailbox);
        Files.writeString(mailbox.hold().resolve("m-in.json"), MESSAGE.replace("m-1", "m-in"));
        record.append(MessageRecord.HELD, "m-in.json", "", "", "held on its way in", MessageRecord.IN);
        Files.writeString(mailbox.hold().resolve("m-out.json"), MESSAGE.replace("m-1", "m-out"));
        record.append(MessageRecord.HELD, "m-out.json", "", "", "held for a person", MessageRecord.OUT);
        // Held with no line of its own: intake's, which only ever holds what this task sent.
        Files.writeString(mailbox.hold().resolve("m-intake.json"), MESSAGE.replace("m-1", "m-intake"));
        // Held before the record kept directions: nothing can say, so nothing is said.
        Files.writeString(mailbox.hold().resolve("m-old.json"), MESSAGE.replace("m-1", "m-old"));
        record.append(MessageRecord.HELD, "m-old.json", "", "held long ago");
        // The filter checks what a task sends.
        Files.createDirectories(mailbox.rejected());
        Files.writeString(mailbox.rejected().resolve("m-refused.json"), MESSAGE.replace("m-1", "m-refused"));

        final MessageRead read = new MessageRead();

        assertThat(read.read(mailbox, "m-in").direction()).isEqualTo("in");
        assertThat(read.read(mailbox, "m-out").direction()).isEqualTo("out");
        assertThat(read.read(mailbox, "m-intake").direction()).isEqualTo("out");
        assertThat(read.read(mailbox, "m-old").direction()).isEmpty();
        assertThat(read.read(mailbox, "m-refused").direction()).isEqualTo("out");
        assertThat(read.list(mailbox)).extracting(MessageRead.Held::direction).containsExactly("in", "out", "", "out", "out");
    }

    @Test
    void aRecordWrittenBeforeDirectionsStillVerifiesAndADirectionIsCoveredByTheChain(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(MessageRecord.HELD, "m-old.json", "", "held long ago");
        record.append(MessageRecord.HELD, "m-in.json", "", "", "held on its way in", MessageRecord.IN);
        assertThat(record.firstBrokenLine()).isZero();

        // Turning an incoming message into an outgoing one after the fact breaks the chain at that line.
        final Path file = mailbox.record().resolve(MessageRecord.FILE);
        Files.writeString(file, Files.readString(file).replace("\"direction\":\"in\"", "\"direction\":\"out\""));
        assertThat(record.firstBrokenLine()).isEqualTo(2);
    }

    @Test
    void anEmptyMailboxListsNothing(@TempDir final Path dir) throws IOException {
        assertThat(new MessageRead().list(mailbox(dir))).isEmpty();
    }

    @Test
    void takesTheFileNameAsAReleaseDoes(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.hold().resolve("m-1.json"), MESSAGE);

        assertThat(new MessageRead().read(mailbox, "m-1.json").outcome()).isEqualTo(MessageRead.Outcome.FOUND);
    }

    @Test
    void readsWhatTheFilterRefusedInFullWithTheFiltersOwnAnswer(@TempDir final Path dir) throws IOException {
        // The operator's decision: a person sees the full text of a refused message, to decide about it.
        final Mailbox mailbox = mailbox(dir);
        Files.createDirectories(mailbox.rejected());
        Files.writeString(mailbox.rejected().resolve("m-1.json"), MESSAGE);
        Files.createDirectories(mailbox.inboxCur());
        Files.writeString(mailbox.inboxCur().resolve("answer.json"), """
                {"messageId":"f-1","role":"ROLE_AGENT","parts":[{"text":"refused: secret.aws-key in part 1"},
                 {"data":{"verdict":"refused","rejectedMessageId":"m-1","tool":{"name":"sokar-message-sluice-filter"}}}]}
                """);

        final MessageRead.Held held = new MessageRead().read(mailbox, "m-1");

        assertThat(held.outcome()).isEqualTo(MessageRead.Outcome.FOUND);
        assertThat(held.standing()).isEqualTo("refused-by-filter");
        assertThat(held.text()).startsWith("Can you review PR 12?");
        assertThat(held.reason()).isEqualTo("refused: secret.aws-key in part 1");
    }

    @Test
    void deliversWhatTheFilterRefusedWhenAPersonDecidesStraightToItsTransport(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.createDirectories(mailbox.rejected());
        Files.writeString(mailbox.rejected().resolve("m-1.json"), MESSAGE);
        Files.writeString(mailbox.rejected().resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        final org.fuin.sokar.core.project.Mail mail = new org.fuin.sokar.core.project.Mail(java.util.List.of(
                new org.fuin.sokar.core.project.Mail.Peer("reviewer", "local:reviewer-box", "vouched")));

        final MessageRelease.Result result = new MessageRelease().decide(mailbox, "m-1", false, mail);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.DELIVERED_DESPITE_FILTER);
        // Never through 'accepted/', which means the filter passed it.
        assertThat(mailbox.accepted().resolve("m-1.json")).doesNotExist();
        assertThat(mailbox.queueActive("local").resolve("m-1.json")).exists();
        assertThat(mailbox.queueActive("local").resolve("m-1.json.sig")).exists();
        assertThat(new MessageRecord(mailbox).entries()).anyMatch(line -> MessageRecord.OVERRIDDEN.equals(line.get("event"))
                && "reviewer".equals(line.get("peer")));
    }

    @Test
    void neverSendsWhatAPersonRefusedForGoodOrWhatTheFilterCouldNotCheck(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.hold().resolve("m-1.json"), MESSAGE);
        final org.fuin.sokar.core.project.Mail mail = new org.fuin.sokar.core.project.Mail(java.util.List.of(
                new org.fuin.sokar.core.project.Mail.Peer("reviewer", "local:reviewer-box", "vouched")));
        assertThat(new MessageRelease().decide(mailbox, "m-1", true, mail).outcome())
                .isEqualTo(MessageRelease.Outcome.REFUSED);
        Files.createDirectories(mailbox.error());
        Files.write(mailbox.error().resolve("broken.json"), new byte[] { (byte) 0xff, 'x' });

        assertThat(new MessageRelease().decide(mailbox, "m-1", false, mail).outcome())
                .isEqualTo(MessageRelease.Outcome.NOT_DELIVERABLE);
        assertThat(new MessageRelease().decide(mailbox, "broken.json", false, mail).outcome())
                .isEqualTo(MessageRelease.Outcome.NOT_DELIVERABLE);
        // Both still readable, as text: the unchecked one even though it is not UTF-8.
        assertThat(new MessageRead().read(mailbox, "m-1").standing()).isEqualTo("refused-by-person");
        assertThat(new MessageRead().read(mailbox, "broken.json").standing()).isEqualTo("unchecked");
        assertThat(mailbox.queueActive("local")).doesNotExist();
    }

    @Test
    void refusesToDeliverWithoutTheSignatureTheHostMadeAtIntake(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.createDirectories(mailbox.rejected());
        Files.writeString(mailbox.rejected().resolve("m-1.json"), MESSAGE);
        final org.fuin.sokar.core.project.Mail mail = new org.fuin.sokar.core.project.Mail(java.util.List.of(
                new org.fuin.sokar.core.project.Mail.Peer("reviewer", "local:reviewer-box", "vouched")));

        final MessageRelease.Result result = new MessageRelease().decide(mailbox, "m-1", false, mail);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.NOT_DELIVERABLE);
        assertThat(result.detail()).contains("signature");
    }

    @Test
    void asksWhichOneWhenTwoAnswerToTheSameId(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = mailbox(dir);
        Files.writeString(mailbox.hold().resolve("m-1.json"), MESSAGE);
        Files.writeString(mailbox.hold().resolve("m-1-again.json"), MESSAGE);

        assertThat(new MessageRead().read(mailbox, "m-1").outcome()).isEqualTo(MessageRead.Outcome.AMBIGUOUS);
    }
}
