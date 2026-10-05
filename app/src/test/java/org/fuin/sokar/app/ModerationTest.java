package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.project.Egress;
import org.fuin.sokar.core.project.Limits;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link Moderation}.
 */
class ModerationTest {

    /** Reached through the project's conversation: its room. */
    private static final Mail.Peer READER = new Mail.Peer("reader", "matrix:", Mail.Peer.EXTERNAL);

    /** Reached anywhere else. */
    private static final Mail.Peer OPS = new Mail.Peer("ops", "mail:ops@example.org", Mail.Peer.EXTERNAL);

    private Project project(final SecurityClass securityClass, final Mail mail) {
        return new Project("p", "a project", securityClass, "ubuntu:24.04", null,
                // An online project needs one, and this test is about what a mode may be, not
                // about where work goes.
                securityClass == SecurityClass.ONLINE ? "git@example.org:p.git" : null,
                Limits.defaults(), Egress.none(), Project.DEFAULT_PACKAGE_SOURCES, mail);
    }

    private Project guarded(final Mail.Rules rules, final Mail.Peer... peers) {
        return project(SecurityClass.GUARDED, new Mail(List.of(peers), Map.of(), false, rules));
    }

    private Moderation moderation(final Path dir, final Project project) {
        return new Moderation(dir.resolve("moderation").resolve("p.json"), project);
    }

    @Test
    void aFileThatSetsNothingSendsToTheProjectAndItsRoomAndRefusesStrangers(
            @TempDir final Path dir) throws IOException {

        // Decided by the operator on 2026-10-04: set once in project.yml, with defaults good enough to leave alone.
        final Moderation moderation = moderation(dir, guarded(Mail.Rules.UNSAID, READER, OPS));

        assertThat(moderation.whyNotNow("sokar-p-2")).as("another task of the project").isEmpty();
        assertThat(moderation.whyNotNow("reader")).as("only what the filter flags waits for a person").isEmpty();
        assertThat(moderation.whyNotNow("ops")).contains("are refused");
        assertThat(moderation.refuses("ops")).isTrue();
    }

    @Test
    void theProjectsRulesAndAPeersOwnModeDecide(@TempDir final Path dir) throws IOException {
        final Moderation moderation = moderation(dir, guarded(new Mail.Rules(Mail.PROMPT, null, Mail.PROMPT),
                READER, new Mail.Peer("auditor", "mail:audit@example.org", Mail.Peer.EXTERNAL, 10, Mail.DENY), OPS));

        assertThat(moderation.peer("sokar-p-2").mode()).isEqualTo(Mail.PROMPT);
        assertThat(moderation.peer("ops").mode()).isEqualTo(Mail.PROMPT);
        assertThat(moderation.peer("auditor").mode()).isEqualTo(Mail.DENY);
    }

    @Test
    void aPersonCanStillHoldAPeerWhateverTheProjectSays(@TempDir final Path dir) throws IOException {
        final Project project = guarded(Mail.Rules.UNSAID, READER);
        final Moderation moderation = moderation(dir, project);

        final Moderation.Change change = moderation.set("sokar-p-2", true, null, project);

        assertThat(change.refused()).isEmpty();
        assertThat(moderation.peer("sokar-p-2").mode()).as("the mode is the project's, not lost").isEqualTo(Mail.ALLOW);
        assertThat(moderation.whyNotNow("sokar-p-2")).contains("held until a person releases it");
        moderation.set("sokar-p-2", false, null, project);
        assertThat(moderation.whyNotNow("sokar-p-2")).isEmpty();
    }

    @Test
    void aModeIsNoLongerSetOnTheHostButInTheProject(@TempDir final Path dir) throws IOException {
        final Project project = guarded(Mail.Rules.UNSAID, OPS);
        final Moderation moderation = moderation(dir, project);

        final Moderation.Change change = moderation.set("ops", null, Mail.ALLOW, project);

        assertThat(change.peer()).isNull();
        assertThat(change.refused()).contains("project.yml").contains("mail.rules").contains("mail.peers.ops.mode");
        assertThat(moderation.peer("ops").mode()).as("nothing was changed").isEqualTo(Mail.DENY);
    }

    @Test
    void aModeAPersonSetOnTheHostBeforeIsNoLongerRead(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("moderation").resolve("p.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"peers\":{\"ops\":{\"mode\":\"allow\",\"held\":true}}}", StandardCharsets.UTF_8);

        final Moderation moderation = new Moderation(file, guarded(Mail.Rules.UNSAID, OPS));

        assertThat(moderation.peer("ops").mode()).as("the project's rule").isEqualTo(Mail.DENY);
        assertThat(moderation.peer("ops").held()).as("the brake is still the host's").isTrue();
    }

    @Test
    void withoutItsProjectEveryPeerWaitsForAPerson(@TempDir final Path dir) throws IOException {
        // A mailbox whose project is gone: nothing says how closely it is watched, so it is watched most.
        final Moderation moderation = new Moderation(dir.resolve("moderation").resolve("gone.json"));

        assertThat(moderation.whyNotNow("sokar-gone-2")).contains("waits for a person");
    }

    /**
     * Turning moderation off must not read as turning the filter off - nothing here can do that.
     */
    @Test
    void offStillLeavesTheFilterInTheWay(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        final Moderation moderation = moderation(dir, project(SecurityClass.ONLINE,
                new Mail(List.of(OPS), Map.of(), false, new Mail.Rules(null, null, Mail.OFF))));

        assertThat(moderation.whyNotNow("ops")).as("moderation asks nothing now").isEmpty();
        // And the filter is a step no mode reaches: with none installed, still nothing is sent.
        assertThat(new MessageFiltering(new FakeCommandRunner(), null).run(mailbox, false).ran())
                .isFalse();
    }

    @Test
    void aHoldIsTheProjectsAndATaskStartedLaterFindsIt(@TempDir final Path dir) throws IOException {
        // Decided by the operator on 2026-09-29: held for the project, not for one task's mailbox.
        final SokarPaths paths = new SokarPaths(org.fuin.sokar.core.config.XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir), dir.resolve("bin"));
        final Project project = guarded(Mail.Rules.UNSAID, READER);
        Moderation.of(paths, project).set("reader", Boolean.TRUE, null, project);

        assertThat(Moderation.of(paths, project).peer("reader").held()).as("the same project, asked again").isTrue();
        assertThat(Moderation.of(paths, "q").peer("reader").held()).as("another project").isFalse();
        // On the host, and in no task's mailbox, which lives under the runtime directory.
        assertThat(paths.messaging().moderation("p")).startsWith(dir.resolve("state"));
    }

    @Test
    void aPersonInTheConversationIsWatchedAsTheRoomIs(@TempDir final Path dir)
            throws IOException {

        // A direct chat with michi leaves the machine as a message to the room does (the operator, 2026-10-04).
        final Moderation moderation = new Moderation(dir.resolve("moderation").resolve("p.json"),
                guarded(new Mail.Rules(null, Mail.PROMPT, null), READER), java.util.Set.of("michi"));

        assertThat(moderation.peer("michi").mode()).as("the room's written rule").isEqualTo(Mail.PROMPT);
        assertThat(moderation.peer("sokar-p-2").mode()).as("a task of the project still talks freely")
                .isEqualTo(Mail.ALLOW);
    }
}
