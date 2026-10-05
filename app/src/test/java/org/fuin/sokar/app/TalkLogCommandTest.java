package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Test for {@link TalkLogCommand}: a task's conversation as it happened, in order, never what a message says.
 */
class TalkLogCommandTest {

    private static final String TASK = "sokar-p-t";

    private final StringWriter out = new StringWriter();

    @Test
    void showsWhatHappenedToEachMessageInOrderAndNeverWhatItSays(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(TASK));
        mailbox.create();
        Files.writeString(mailbox.hold().resolve("m-1.json"), """
                {"messageId":"m-1","role":"ROLE_AGENT","parts":[{"text":"the secret plan"}],"metadata":{"to":"reviewer"}}
                """);
        final MessageRecord record = new MessageRecord(mailbox);
        record.append(MessageRecord.HELD, "m-1.json", "m-1", "rev\u001b[2Jiewer", "held for a person");
        record.append(MessageRecord.SENT, "m-1.json", "m-1", "reviewer", "matrix");

        assertThat(execute(context)).isEqualTo(0);

        final String said = out.toString();
        assertThat(said).contains("EVENT").contains("held").contains("sent");
        assertThat(said.indexOf("held   ")).isLessThan(said.indexOf("sent   "));
        assertThat(said).contains("rev\\x1b[2Jiewer").doesNotContain("\u001b").doesNotContain("the secret plan");
    }

    @Test
    void saysSoWhenThereIsNoMessageYet(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        new Mailbox(context.paths().messaging().mailbox(TASK)).create();

        assertThat(execute(context)).isEqualTo(0);
        assertThat(out.toString()).contains("no message yet");
    }

    private int execute(final SokarContext context) {
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd.execute("talk", "log", TASK);
    }

    private static SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
