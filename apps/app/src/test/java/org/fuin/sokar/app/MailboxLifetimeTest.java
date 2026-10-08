package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a mailbox survives, and what ends it.
 * <p>
 * The point of this test is a lifetime rather than a function: a conversation has to outlive the
 * container it was held from, so stopping a task must leave it and only removing the task may take
 * it. Asserted on the files, because that is what an agent finds when it comes back.
 */
class MailboxLifetimeTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private Path withMessage(final SokarContext context, final String container) throws IOException {
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        final Path read = mailbox.inboxCur().resolve("one.json");
        Files.writeString(read, "{}");
        return read;
    }

    @Test
    void stopping_a_task_leaves_its_conversation_where_it_was(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path message = withMessage(context, "sokar-uc-shell");
        runner.answering("ps", "sokar-uc-shell\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n");

        new TaskControl(context).stop("sokar-uc-shell");

        assertThat(message).as("what the agent had read is still there after a stop").exists();
    }

    @Test
    void removing_the_task_is_what_ends_it(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path message = withMessage(context, "sokar-uc-shell");
        runner.answering("ps", "sokar-uc-shell\tExited (143)\t1700000000\t1700000100\tuc\tguarded\n");

        new TaskControl(context).remove("sokar-uc-shell", false, true);

        assertThat(message).doesNotExist();
        assertThat(context.paths().messaging().mailbox("sokar-uc-shell")).doesNotExist();
    }

    @Test
    void a_mailbox_is_not_in_the_directory_a_reboot_clears(@TempDir final Path dir) {
        final SokarContext context = context(dir);

        final Path mailbox = context.paths().messaging().mailbox("sokar-uc-shell");

        // Compared as names rather than with AssertJ's path assertion, which resolves the real
        // path and therefore needs the directory to exist - and the point here is where a mailbox
        // WOULD go, before any task has made one.
        assertThat(mailbox.startsWith(dir.resolve("state"))).as("under the state directory").isTrue();
        assertThat(mailbox.startsWith(dir.resolve("run"))).isFalse();
        assertThat(mailbox.startsWith(context.paths().tasks().containerState("sokar-uc-shell"))).isFalse();
    }
}
