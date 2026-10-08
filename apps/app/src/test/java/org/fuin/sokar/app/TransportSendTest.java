package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link TransportSend}, whose whole contract is three exit codes.
 */
class TransportSendTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private TransportDirectory withAdapter(final Path dir) throws IOException {
        final Path transports = dir.resolve("transports");
        Files.createDirectories(transports);
        final Path adapter = transports.resolve(TransportDirectory.PREFIX + "local");
        Files.writeString(adapter, "#!/bin/sh\n");
        Files.setPosixFilePermissions(adapter, PosixFilePermissions.fromString("rwx------"));
        return new TransportDirectory(List.of(transports));
    }

    private Mailbox queued(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        final Path active = mailbox.queueActive("local");
        Files.createDirectories(active);
        Files.writeString(active.resolve("m-1.json"), "{\"messageId\":\"m-1\"}");
        Files.writeString(active.resolve("m-1.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        Files.writeString(active.resolve("m-1.json.to"), "/peer/inbound");
        return mailbox;
    }

    @Test
    void a_message_the_transport_took_moves_to_sent_with_its_receipt(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = queued(dir);
        runner.answering("send", "{\"delivered\":true}");

        final TransportSend.Result result =
                new TransportSend(runner, withAdapter(dir)).send(mailbox, "local");

        assertThat(result.sent()).containsExactly("m-1.json");
        assertThat(mailbox.sent().resolve("m-1.json")).exists();
        assertThat(mailbox.sent().resolve("m-1.json.sig")).as("the evidence travels with it").exists();
        assertThat(mailbox.sent().resolve("m-1.json.receipt.json")).hasContent("{\"delivered\":true}");
        assertThat(mailbox.queueActive("local")).isEmptyDirectory();
    }

    @Test
    void a_temporary_failure_waits_and_is_tried_again(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = queued(dir);
        final TransportDirectory transports = withAdapter(dir);
        runner.failing("send", TransportSend.TEMPORARY, "the mailbox is not writable yet");

        final TransportSend.Result first = new TransportSend(runner, transports).send(mailbox, "local");

        assertThat(first.deferred()).containsExactly("m-1.json");
        assertThat(mailbox.queueDeferred("local").resolve("m-1.json")).exists();

        // The next pass finds it again, and this time the far side is there.
        final FakeCommandRunner later = new FakeCommandRunner().answering("send", "{}");
        final TransportSend.Result second = new TransportSend(later, transports).send(mailbox, "local");

        assertThat(second.sent()).containsExactly("m-1.json");
        assertThat(mailbox.queueDeferred("local")).isEmptyDirectory();
    }

    @Test
    void a_permanent_failure_is_not_retried(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = queued(dir);
        runner.failing("send", 1, "no such destination");

        final TransportSend.Result result =
                new TransportSend(runner, withAdapter(dir)).send(mailbox, "local");

        assertThat(result.refused()).singleElement()
                .satisfies(held -> assertThat(held.reason()).contains("no such destination"));
        assertThat(mailbox.hold().resolve("m-1.json")).exists();
        assertThat(mailbox.queueActive("local")).isEmptyDirectory();
        assertThat(mailbox.queueDeferred("local")).doesNotExist();
    }

    /**
     * A machine without the adapter has lost nothing. The message waits, and installing the
     * transport later is what makes the queue move - it is not a refusal, and the sender is not
     * told the peer is gone.
     */
    @Test
    void a_transport_that_is_not_installed_leaves_the_message_waiting(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = queued(dir);

        final TransportSend.Result result = new TransportSend(runner,
                new TransportDirectory(List.of(dir.resolve("none")))).send(mailbox, "local");

        assertThat(result.deferred()).containsExactly("m-1.json");
        assertThat(result.refused()).isEmpty();
        assertThat(mailbox.queueDeferred("local").resolve("m-1.json")).exists();
        assertThat(runner.invocations()).as("nothing was run").isEmpty();
    }

    @Test
    void a_transport_that_shows_a_line_to_a_person_is_given_one_and_one_that_does_not_is_not(@TempDir final Path dir)
            throws IOException {

        // A person reading the room saw the message as JSON: the line they read is Sokar's, made here from the
        // checked message alone, and passed only to a transport that says it takes it.
        final Mailbox mailbox = queued(dir);
        runner.answering("describe", "{\"scheme\":\"local\",\"shown\":true}");
        runner.answering("send", "{}");

        new TransportSend(runner, withAdapter(dir)).send(mailbox, "local");

        assertThat(runner.lines()).filteredOn(line -> line.contains(" send ")).singleElement(
                org.assertj.core.api.InstanceOfAssertFactories.STRING).contains("--shown ");
        assertThat(mailbox.queueActive("local")).as("nothing of it is left behind").isEmptyDirectory();

        final Mailbox other = queued(dir.resolve("other"));
        final FakeCommandRunner plain = new FakeCommandRunner().answering("describe", "{\"scheme\":\"local\"}")
                .answering("send", "{}");
        new TransportSend(plain, withAdapter(dir.resolve("other"))).send(other, "local");
        assertThat(plain.lines()).filteredOn(line -> line.contains(" send ")).singleElement(
                org.assertj.core.api.InstanceOfAssertFactories.STRING).doesNotContain("--shown");
    }

    @Test
    void the_line_a_person_reads_is_the_text_and_nothing_else() {

        // The operator in walk 10, 2026-10-04: "sokar-…-writer to michi:" in front of a direct answer "makes no
        // sense". The account that posts it says who wrote it, and a mention says whom it is for.
        assertThat(TransportSend.shown("""
                {"messageId":"m-1","contextId":"c-1","parts":[{"text":"Hello michi."},{"text":"Second."},
                 {"mediaType":"image/png","data":"iVBOR"}],"metadata":{"kind":"question","from":"michi","to":"michi"}}
                """, "sokar-p-foo")).isEqualTo("Hello michi.\n\nSecond.\n\n[image/png]");
    }

    @Test
    void anAnswerToAPersonsRoomMessageGoesIntoTheRoomMentioningThemAndADirectOneIntoTheirChat(@TempDir final Path dir)
            throws IOException {

        // The operator in walk 10, 2026-10-04: in the room "with the correct @", and "a private answer only when he
        // was written to privately".
        final Mailbox mailbox = queued(dir);
        Files.writeString(mailbox.queueActive("local").resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"michi\",\"via\":\"room\"}}");
        Files.writeString(mailbox.queueActive("local").resolve("m-1.json.to"), "@michi:localhost");
        Files.writeString(mailbox.queueActive("local").resolve("m-2.json"),
                "{\"messageId\":\"m-2\",\"metadata\":{\"to\":\"michi\",\"via\":\"direct\"}}");
        Files.writeString(mailbox.queueActive("local").resolve("m-2.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        Files.writeString(mailbox.queueActive("local").resolve("m-2.json.to"), "@michi:localhost");
        runner.answering("describe", "{\"scheme\":\"local\",\"mention\":true}");
        runner.answering("send", "{}");

        new TransportSend(runner, withAdapter(dir), (transport, container) -> new TransportSend.Acting(
                java.util.Map.of("TOKEN", "t"), "!room:localhost")).send(mailbox, "local");

        final java.util.List<String> sends = runner.invocations().stream().map(command -> command.arguments())
                .filter(arguments -> arguments.contains("send")).map(arguments -> String.join(" ", arguments)).toList();
        assertThat(sends).anySatisfy(line -> assertThat(line).contains("m-1.json.sig --to !room:localhost")
                .contains("--mention @michi:localhost"));
        assertThat(sends).anySatisfy(line -> assertThat(line).contains("m-2.json.sig --to @michi:localhost")
                .doesNotContain("--mention"));
    }

    @Test
    void aMessageToATaskInTheRoomMentionsItsAccountWhereTheTransportTakesAMention(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = queued(dir);
        Files.writeString(mailbox.queueActive("local").resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"review\"}}");
        Files.writeString(mailbox.queueActive("local").resolve("m-1.json.to"), "");
        runner.answering("describe", "{\"scheme\":\"local\",\"shown\":true,\"mention\":true}");
        runner.answering("send", "{}");

        new TransportSend(runner, withAdapter(dir), (transport, container) -> new TransportSend.Acting(
                java.util.Map.of("TOKEN", "t"), "!room:localhost",
                java.util.Map.of("review", "@sokar-p-review:localhost"))).send(mailbox, "local");

        assertThat(runner.only(" send ").arguments()).containsSubsequence("--mention", "@sokar-p-review:localhost");
    }

    @Test
    void aMessageToAPersonGoesToThemAndOneToTheRoomToTheRoom(@TempDir final Path dir) throws IOException {

        // A direct chat (the operator, 2026-10-04): the room is what the task acts in, and a person is a destination of
        // their own, which the host queued from the project's peer; an empty one is the room's.
        final Mailbox mailbox = queued(dir);
        Files.writeString(mailbox.queueActive("local").resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"via\":\"direct\"}}");
        Files.writeString(mailbox.queueActive("local").resolve("m-1.json.to"), "@michi:localhost");
        Files.writeString(mailbox.queueActive("local").resolve("m-2.json"), "{\"messageId\":\"m-2\"}");
        Files.writeString(mailbox.queueActive("local").resolve("m-2.json.sig"), "-----BEGIN SSH SIGNATURE-----");
        Files.writeString(mailbox.queueActive("local").resolve("m-2.json.to"), "");
        runner.answering("send", "{}");

        new TransportSend(runner, withAdapter(dir), (transport, container) -> new TransportSend.Acting(
                java.util.Map.of("TOKEN", "t"), "!room:localhost")).send(mailbox, "local");

        assertThat(runner.invocations()).filteredOn(command -> command.arguments().contains("send"))
                .extracting(command -> command.arguments().get(command.arguments().indexOf("--to") + 1))
                .containsExactlyInAnyOrder("@michi:localhost", "!room:localhost");
    }
}
