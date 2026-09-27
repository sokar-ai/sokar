package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
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

    private Project project(final SecurityClass securityClass, final boolean mayLeave) {
        return new Project("p", "a project", securityClass, "ubuntu:24.04", null,
                // An online project needs one, and this test is about what a mode may be, not
                // about where work goes.
                securityClass == SecurityClass.ONLINE ? "git@example.org:p.git" : null,
                Limits.defaults(), Egress.none(), Project.DEFAULT_PACKAGE_SOURCES, Mail.none(),
                mayLeave);
    }

    private Moderation moderation(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return new Moderation(mailbox);
    }

    @Test
    void a_peer_nobody_decided_about_waits_for_a_person(@TempDir final Path dir)
            throws IOException {
        final Moderation moderation = moderation(dir);

        assertThat(moderation.peer("reviewer").mode()).isEqualTo("prompt");
        assertThat(moderation.peer("reviewer").held()).isFalse();
        assertThat(moderation.whyNotNow("reviewer")).contains("waits for a person");
        assertThat(moderation.refuses("reviewer")).as("waiting is not a refusal").isFalse();
    }

    @Test
    void remembers_what_a_person_decided(@TempDir final Path dir) throws IOException {
        final Moderation moderation = moderation(dir);

        moderation.set("reviewer", null, "allow", project(SecurityClass.ONLINE, false));

        assertThat(moderation.whyNotNow("reviewer")).isEmpty();
        assertThat(moderation.all()).containsKey("reviewer");
    }

    @Test
    void a_held_peer_waits_whatever_its_mode_says(@TempDir final Path dir) throws IOException {
        final Moderation moderation = moderation(dir);
        moderation.set("reviewer", null, "allow", project(SecurityClass.ONLINE, false));

        moderation.set("reviewer", true, null, project(SecurityClass.ONLINE, false));

        assertThat(moderation.peer("reviewer").mode()).as("the mode is remembered, not lost")
                .isEqualTo("allow");
        assertThat(moderation.whyNotNow("reviewer")).contains("held until a person releases it");
    }

    @Test
    void deny_is_a_refusal_the_sender_is_told_about_once(@TempDir final Path dir)
            throws IOException {
        final Moderation moderation = moderation(dir);

        moderation.set("ops", null, "deny", project(SecurityClass.GUARDED, false));

        assertThat(moderation.whyNotNow("ops")).contains("are refused");
        assertThat(moderation.refuses("ops")).isTrue();
    }

    /**
     * The setting the gate and the mailbox share: unread work leaving is one decision, written down once.
     */
    @Test
    void a_guarded_project_must_opt_in_before_anything_leaves_unread(@TempDir final Path dir)
            throws IOException {
        final Moderation moderation = moderation(dir);

        final Moderation.Change change =
                moderation.set("reviewer", null, "allow", project(SecurityClass.GUARDED, false));

        assertThat(change.peer()).isNull();
        assertThat(change.refused()).contains("unread_work_may_leave");
        assertThat(moderation.peer("reviewer").mode()).as("nothing was changed").isEqualTo("prompt");
    }

    @Test
    void a_guarded_project_that_opted_in_may_allow(@TempDir final Path dir) throws IOException {
        final Moderation moderation = moderation(dir);

        assertThat(moderation.set("reviewer", null, "off", project(SecurityClass.GUARDED, true))
                .refused()).isEmpty();
        assertThat(moderation.whyNotNow("reviewer")).isEmpty();
    }

    /**
     * Turning moderation off must not read as turning the filter off - nothing here can do that.
     */
    @Test
    void off_still_leaves_the_filter_in_the_way(@TempDir final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        final Moderation moderation = new Moderation(mailbox);
        moderation.set("reviewer", null, "off", project(SecurityClass.ONLINE, false));

        assertThat(moderation.whyNotNow("reviewer")).as("moderation asks nothing now").isEmpty();
        // And the filter is a step no mode reaches: with none installed, still nothing is sent.
        assertThat(new MessageFiltering(new FakeCommandRunner(), null).run(mailbox).ran())
                .isFalse();
    }

    @Test
    void refuses_a_mode_it_does_not_know(@TempDir final Path dir) throws IOException {
        final Moderation.Change change = moderation(dir)
                .set("reviewer", null, "sometimes", project(SecurityClass.ONLINE, false));

        assertThat(change.peer()).isNull();
        assertThat(change.refused()).contains("prompt");
    }
}
