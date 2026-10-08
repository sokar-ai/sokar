package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageWatch}.
 */
class MessageWatchTest {

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void the_interval_is_a_minute_unless_somebody_says_otherwise() {
        assertThat(MessageWatch.interval(name -> null)).isEqualTo(Duration.ofSeconds(60));
        assertThat(MessageWatch.interval(Map.of(MessageWatch.INTERVAL_VARIABLE, "5")::get))
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(MessageWatch.interval(Map.of(MessageWatch.INTERVAL_VARIABLE, "0")::get))
                .isEqualTo(Duration.ZERO);
    }

    /**
     * A misspelt value falls back rather than turning messages off: the failure that would be found
     * weeks later as "nothing ever arrives" is worse than one that ignores a typo.
     */
    @Test
    void a_value_nobody_can_read_falls_back_to_the_default() {
        assertThat(MessageWatch.interval(Map.of(MessageWatch.INTERVAL_VARIABLE, "often")::get))
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void a_machine_with_no_mailboxes_does_nothing(@TempDir final Path dir) {
        assertThat(new MessageWatch(context(dir), Duration.ofSeconds(60)).passOnce()).isZero();
    }

    /**
     * The reason the mailbox is not in the runtime directory: a stopped task still receives, and
     * finds its post when it comes back.
     * <p>
     * Where in the mailbox it finds it depends on what the host can say about the sender. Here
     * there is no project, so the host cannot say the peer is one it vouches for - and an unvouched
     * peer's message is checked before an agent reads it. With no filter installed either, it waits
     * in {@code hold/}. What this test is about is that it arrived and was not lost.
     */
    @Test
    void a_stopped_task_still_has_its_post_delivered(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox("sokar-demo-stopped"));
        mailbox.create();
        final SigningKey peer = SigningKey.generate("peer");
        final String json = "{\"messageId\":\"in-1\"}";
        Files.writeString(mailbox.inbound().resolve("in-1.json"), json);
        Files.writeString(mailbox.inbound().resolve("in-1.json.sig"), SshSignature.sign(peer,
                json.getBytes(StandardCharsets.UTF_8), MessageIntake.NAMESPACE));
        Files.createDirectories(context.paths().messaging().allowedSigners().getParent());
        Files.writeString(context.paths().messaging().allowedSigners(),
                "reviewer " + peer.authorizedKeysLine() + "\n");

        final int moved = new MessageWatch(context, Duration.ofSeconds(60)).passOnce();

        assertThat(moved).isEqualTo(1);
        assertThat(mailbox.inbound().resolve("in-1.json")).as("it was taken in").doesNotExist();
        assertThat(mailbox.hold().resolve("in-1.json"))
                .as("waiting for the task to come back, and for somebody to vouch for the sender")
                .exists();
    }

    /**
     * One mailbox's trouble is not another's. A keyring nobody can read stops every mailbox, by
     * design, but a project file that is gone stops only sending from that one.
     */
    @Test
    void a_mailbox_whose_project_is_gone_still_receives(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Mailbox orphan = new Mailbox(context.paths().messaging().mailbox("sokar-nosuch-task"));
        orphan.create();
        Files.writeString(orphan.outboxNew().resolve("m-1.json"),
                "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"reviewer\"}}");

        assertThat(new MessageWatch(context, Duration.ofSeconds(60)).passOnce()).isEqualTo(1);
        assertThat(orphan.incoming().resolve("m-1.json")).as("signed and kept, not sent").exists();
    }
}
