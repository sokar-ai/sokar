package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link InboundCheck}.
 */
class InboundCheckTest {

    private final Mail mail = new Mail(List.of(
            new Mail.Peer("reviewer", "local:/peer/inbound", "vouched"),
            new Mail.Peer("ops", "local:/ops/inbound", "external")));

    private final Path filter = Path.of("/usr/libexec/sokar/sokar-message-sluice-filter");

    /** A filter that moves what it is given wherever the test says, the way the real one does. */
    private CommandRunner filterThat(final Mailbox mailbox, final boolean accepts) {
        return command -> {
            try (Stream<Path> waiting = Files.list(mailbox.checkIncoming())) {
                for (final Path message : waiting.toList()) {
                    final Path target = accepts ? mailbox.checkAccepted() : mailbox.checkRejected();
                    Files.move(message, target.resolve(message.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            return new CommandResult(command, accepts ? MessageFiltering.ALL_ACCEPTED
                    : MessageFiltering.SOMETHING_REFUSED, "", "");
        };
    }

    private Mailbox arrived(final Path dir, final String name) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        Files.writeString(mailbox.inbound().resolve(name), "{\"messageId\":\"m-1\"}");
        return mailbox;
    }

    @Test
    void a_vouched_peers_message_is_not_checked_again(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");
        final FakeCommandRunner runner = new FakeCommandRunner();

        final String refused = new InboundCheck(runner, filter, mail)
                .refuse(mailbox, mailbox.inbound().resolve("m-1.json"), "reviewer");

        assertThat(refused).isEmpty();
        assertThat(runner.invocations()).as("that machine already did it").isEmpty();
    }

    @Test
    void an_external_peers_message_goes_through_the_filter(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");

        final String refused = new InboundCheck(filterThat(mailbox, true), filter, mail)
                .refuse(mailbox, mailbox.inbound().resolve("m-1.json"), "ops");

        assertThat(refused).isEmpty();
        assertThat(mailbox.checkIncoming()).as("nothing is left behind").isEmptyDirectory();
        assertThat(mailbox.checkAccepted()).isEmptyDirectory();
    }

    @Test
    void what_the_filter_refuses_on_the_way_in_is_not_delivered(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");

        final String refused = new InboundCheck(filterThat(mailbox, false), filter, mail)
                .refuse(mailbox, mailbox.inbound().resolve("m-1.json"), "ops");

        assertThat(refused).contains("refused it on the way in");
    }

    /**
     * Fail closed, in the direction that is easy to forget: a machine that cannot check does not
     * get to skip checking.
     */
    @Test
    void without_a_filter_an_external_peers_message_is_held(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = arrived(dir, "m-1.json");

        final String refused = new InboundCheck(new FakeCommandRunner(), null, mail)
                .refuse(mailbox, mailbox.inbound().resolve("m-1.json"), "ops");

        assertThat(refused).contains("no message filter is installed");
    }

    /**
     * And the whole way through: a refusal on the way in means the agent never sees it.
     */
    @Test
    void delivery_holds_what_the_check_refused(@TempDir final Path dir) throws IOException {
        final SigningKey opsKey = SigningKey.generate("ops");
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        final String json = "{\"messageId\":\"m-1\"}";
        Files.writeString(mailbox.inbound().resolve("m-1.json"), json);
        Files.writeString(mailbox.inbound().resolve("m-1.json.sig"), SshSignature.sign(opsKey,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));

        final MessageDelivery.Outcome outcome = new MessageDelivery().deliver(mailbox,
                List.of(new MessageDelivery.Peer("ops", List.of(opsKey.keyBlob()))),
                java.util.Set.of(),
                new InboundCheck(filterThat(mailbox, false), filter, mail));

        assertThat(outcome.delivered()).isEmpty();
        assertThat(outcome.held()).singleElement().satisfies(held ->
                assertThat(held.reason()).contains("refused it on the way in"));
        assertThat(mailbox.inboxNew()).isEmptyDirectory();
        assertThat(mailbox.hold().resolve("m-1.json")).exists();
    }
}
