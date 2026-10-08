package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageRelease}.
 */
class MessageReleaseTest {

    private final MessageRelease release = new MessageRelease();

    private Mailbox held(final Path dir, final String name, final String json) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        Files.writeString(mailbox.hold().resolve(name), json);
        Files.writeString(mailbox.hold().resolve(name + ".sig"),
                "-----BEGIN SSH SIGNATURE-----");
        return mailbox;
    }

    @Test
    void a_released_message_goes_back_where_the_filter_left_it(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = held(dir, "m-1.json", "{\"messageId\":\"m-1\"}");

        final MessageRelease.Result result = release.decide(mailbox, "m-1", false);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.RELEASED);
        assertThat(result.id()).isEqualTo("m-1");
        assertThat(mailbox.accepted().resolve("m-1.json")).exists();
        assertThat(mailbox.accepted().resolve("m-1.json.sig")).as("its signature travels with it")
                .exists();
        assertThat(mailbox.hold().resolve("m-1.json")).doesNotExist();
    }

    @Test
    void a_refused_message_is_kept_and_its_sender_told(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = held(dir, "m-2.json", "{\"messageId\":\"m-2\"}");

        final MessageRelease.Result result = release.decide(mailbox, "m-2.json", true);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.REFUSED);
        assertThat(mailbox.rejected().resolve("m-2.json")).exists();
        assertThat(mailbox.feedback()).isNotEmptyDirectory();
        assertThat(new BounceDelivery().deliver(mailbox)).hasSize(1);
        assertThat(Files.list(mailbox.inboxNew()).toList()).hasSize(1);
    }

    @Test
    void a_person_who_says_why_they_refused_has_the_sender_told_their_words(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = held(dir, "m-3.json", "{\"messageId\":\"m-3\"}");

        new MessageRelease().because("  it names the customer  ").decide(mailbox, "m-3", true);
        new BounceDelivery().deliver(mailbox);

        final String told = Files.readString(Files.list(mailbox.inboxNew()).toList().get(0));
        assertThat(told).contains("a person refused to send it. They said: it names the customer");
    }

    @Test
    void a_person_note_reaches_the_inbox_as_a_persons_words_never_a_peers(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();

        new PersonNote().tell(mailbox, "look at the failing test first", null);
        new PersonNote().rejected(mailbox, "t", "the migration drops a column");

        final java.util.List<String> notes = Files.list(mailbox.inboxNew()).sorted().map(each -> {
            try {
                return Files.readString(each);
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }).toList();
        assertThat(notes).hasSize(2).allSatisfy(each -> assertThat(each)
                .contains("\"role\":\"ROLE_USER\"").contains("\"from\":\"person\""));
        assertThat(notes).anySatisfy(each -> assertThat(each).contains("look at the failing test first"));
        assertThat(notes).anySatisfy(each -> assertThat(each).contains("\"verdict\":\"rejected\"")
                .contains("They said: the migration drops a column"));
        assertThat(mailbox.inboxTmp()).as("nothing half-written left behind").isEmptyDirectory();
    }

    @Test
    void rejected_work_is_told_to_the_task_it_is_named_after_and_nothing_is_told_when_it_is_gone(
            @TempDir final Path dir) throws IOException {
        final org.fuin.sokar.core.config.XdgPaths xdg = org.fuin.sokar.core.config.XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(new org.fuin.sokar.testing.FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        final org.fuin.sokar.core.project.Project project = org.fuin.sokar.core.project.ProjectReader.read(
                new java.io.StringReader("project:\n  name: \"p\"\n  security_class: \"guarded\"\n"
                        + "image:\n  base_image: \"ubuntu:24.04\"\n"), "project.yml");
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(context.tasks().containerName(project, "t")));
        mailbox.create();

        assertThat(PersonNote.rejectedWork(context, project, "t", "not this way")).isTrue();
        assertThat(Files.readString(Files.list(mailbox.inboxNew()).toList().get(0)))
                .contains("rejected the work you handed over as 't'").contains("They said: not this way");
        assertThat(PersonNote.rejectedWork(context, project, "enroll-sokar-host", null))
                .as("a pending push no task made - an enrolment - tells nobody").isFalse();
    }

    @Test
    void says_so_when_nothing_held_is_called_that(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = held(dir, "m-3.json", "{\"messageId\":\"m-3\"}");

        assertThat(release.decide(mailbox, "m-9", false).outcome())
                .isEqualTo(MessageRelease.Outcome.NO_SUCH_MESSAGE);
    }

    @Test
    void asks_which_one_when_two_answer_to_the_same_id(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = held(dir, "m-4.json", "{\"messageId\":\"m-4\"}");
        Files.writeString(mailbox.hold().resolve("m-4-again.json"), "{\"messageId\":\"m-4\"}");

        assertThat(release.decide(mailbox, "m-4", false).outcome())
                .isEqualTo(MessageRelease.Outcome.AMBIGUOUS);
        assertThat(mailbox.accepted()).as("nothing was moved on a guess").isEmptyDirectory();
    }

    @Test
    void a_message_held_on_its_way_in_is_released_to_this_task_never_out(@TempDir final Path dir)
            throws IOException {

        // Found by Agent Matrix: released, it turned outgoing and was held as "may not address" its own task.
        final Mailbox mailbox = held(dir, "m-7.json", "{\"messageId\":\"m-7\",\"metadata\":{\"to\":\"t\"}}");
        new MessageRecord(mailbox).append(MessageRecord.HELD, "m-7.json", "m-7", "", "it is signed by a key no"
                + " peer is allowed to use", MessageRecord.IN);

        final MessageRelease.Result result = release.decide(mailbox, "m-7", false);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.RELEASED);
        assertThat(result.detail()).isEqualTo(MessageRelease.INWARD);
        assertThat(mailbox.inbound().resolve("m-7.json")).exists();
        assertThat(mailbox.inbound().resolve("m-7.json.sig")).exists();
        assertThat(mailbox.accepted().resolve("m-7.json")).as("never out").doesNotExist();
    }

    @Test
    void a_message_from_a_task_outside_the_conversation_is_not_released(@TempDir final Path dir)
            throws IOException {

        // Released, queued, run without the task's account and held again, for ever: each release was for nothing.
        final Mailbox mailbox = held(dir, "m-1.json", "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"michi\"}}");
        final org.fuin.sokar.core.project.Mail mail = new org.fuin.sokar.core.project.Mail(java.util.List.of(
                new org.fuin.sokar.core.project.Mail.Peer("michi", "matrix:", "external")),
                java.util.Map.of("matrix", java.util.Map.of()));

        final MessageRelease.Result result = new MessageRelease().enrolledIn(java.util.Set.of())
                .decide(mailbox, "m-1", false, mail);

        assertThat(result.outcome()).isEqualTo(MessageRelease.Outcome.NOT_DELIVERABLE);
        assertThat(result.detail()).contains("not in the project's matrix conversation").contains("start it again");
        assertThat(mailbox.hold().resolve("m-1.json")).as("still there to decide about").exists();

        assertThat(new MessageRelease().enrolledIn(java.util.Set.of("matrix")).decide(mailbox, "m-1", false, mail)
                .outcome()).isEqualTo(MessageRelease.Outcome.RELEASED);
    }
}
