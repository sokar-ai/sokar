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
 * Test for {@link TalkHeldCommand}: the terminal's view of the list the daemon's {@code Held} answers.
 */
class TalkHeldCommandTest {

    private static final String TASK = "sokar-p-t";

    private final StringWriter out = new StringWriter();

    @Test
    void listsWhereEachMessageStandsAndWhyWithoutWhatItSays(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(TASK));
        mailbox.create();
        Files.writeString(mailbox.hold().resolve("m-1.json"), """
                {"messageId":"m-1","role":"ROLE_AGENT","parts":[{"text":"the secret plan"}],"metadata":{"to":"reviewer"}}
                """);
        new MessageRecord(mailbox).append(MessageRecord.HELD, "m-1.json", "m-1", "rev\u001b[2Jiewer",
                "held for a person");

        assertThat(execute(context)).isEqualTo(0);

        assertThat(out.toString()).contains("m-1.json   held   rev\\x1b[2Jiewer   held for a person")
                .doesNotContain("the secret plan");
    }

    @Test
    void saysSoWhenNothingIsHeld(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        new Mailbox(context.paths().messaging().mailbox(TASK)).create();

        assertThat(execute(context)).isEqualTo(0);
        assertThat(out.toString()).contains("nothing held");
    }

    private int execute(final SokarContext context) {
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd.execute("talk", "held", TASK);
    }

    private static SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }
}
