package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The peers a task has because of the project it belongs to, rather than because somebody wrote
 * them down.
 * <p>
 * <strong>Why this cannot be a written list.</strong> A project is a unit of work over one or more
 * repositories and a task works on exactly one of them, so two repositories are coordinated by two
 * tasks talking. Those tasks are started and stopped by the machine itself, several a day; a peer
 * list somebody maintained for them would be wrong most of the time, and wrong in the direction
 * where a message silently goes nowhere.
 */
class ProjectTasksArePeersTest {

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

    private static Project project(final String extra) {
        return ProjectReader.read(new StringReader("""
                project:
                  name: "acme"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                repositories:
                  backend:
                  frontend:
                """ + extra), "test");
    }

    /** A project that messages: one peer reached through the project's own conversation on a transport. */
    private static final String CONVERSATION = """
            mail:
              peers:
                person: { address: "room:", trust: external }
            """;

    /** What podman answers when the project has these three tasks. */
    private void tasks(final String... containers) {
        final StringBuilder answer = new StringBuilder();
        for (final String container : containers) {
            answer.append(container).append("\tUp 2 minutes\t\t\t\t\n");
        }
        runner.answering("ps", answer.toString());
    }

    @Test
    void theOtherTasksOfTheProjectArePeersWithoutAnybodyWritingThemDown(@TempDir Path dir) {

        tasks("sokar-acme-planning", "sokar-acme-api", "sokar-acme-web");

        final Mail mail = new MessageWatch(context(dir), Duration.ZERO)
                .withSiblings(project(CONVERSATION), "sokar-acme-planning");

        assertThat(mail.peers()).extracting(Mail.Peer::name)
                .as("and the people in its conversation, whom nobody writes down either")
                .containsExactlyInAnyOrder("person", Mail.PEOPLE, "api", "web");
    }

    @Test
    void aTaskIsNotItsOwnPeer(@TempDir Path dir) {

        tasks("sokar-acme-planning", "sokar-acme-api");

        final Mail mail = new MessageWatch(context(dir), Duration.ZERO)
                .withSiblings(project(CONVERSATION), "sokar-acme-planning");

        assertThat(mail.peers()).extracting(Mail.Peer::name).doesNotContain("planning");
    }

    @Test
    void aTaskOfAnotherProjectIsNotAPeer(@TempDir Path dir) {

        // The bracket is the project. Two projects on one machine are two units of work, and a
        // task that could address the other project's tasks by name would make the bracket
        // mean nothing.
        tasks("sokar-acme-api", "sokar-other-api");

        final Mail mail = new MessageWatch(context(dir), Duration.ZERO)
                .withSiblings(project(CONVERSATION), "sokar-acme-planning");

        assertThat(mail.peers()).extracting(Mail.Peer::name).containsExactly("person", Mail.PEOPLE, "api");
    }

    @Test
    void aStandaloneProjectsTasksAreNobodysPeers(@TempDir Path dir) {

        // No transport that keeps a conversation, so no messaging: the local transport is retired, and a
        // project without a conversation works alone (decided on 2026-09-30).
        tasks("sokar-acme-api", "sokar-acme-web");

        assertThat(new MessageWatch(context(dir), Duration.ZERO).withSiblings(project(""), "sokar-acme-planning")
                .peers()).isEmpty();
    }

    @Test
    void aSiblingIsReachedThroughTheProjectsConversationAndIsVouchedFor(@TempDir Path dir) {

        tasks("sokar-acme-api");

        final Mail.Peer peer = new MessageWatch(context(dir), Duration.ZERO)
                .withSiblings(project(CONVERSATION), "sokar-acme-planning").peer("api");

        assertThat(peer.transport()).isEqualTo("room");
        assertThat(peer.conversation()).isTrue();
        // Both mailboxes belong to this installation and this Unix user, so what arrives was
        // checked where it was written.
        assertThat(peer.external()).isFalse();
    }

    @Test
    void aWrittenPeerOfTheSameNameWins(@TempDir Path dir) {

        tasks("sokar-acme-api");

        // This is how the exception stays possible and stays explicit: naming a task in
        // 'mail.peers' sends to what the file says rather than to the task next door.
        final Mail.Peer peer = new MessageWatch(context(dir), Duration.ZERO)
                .withSiblings(project("""
                        mail:
                          peers:
                            api:
                              address: "spool:someone-else"
                              trust: "external"
                        """), "sokar-acme-planning").peer("api");

        assertThat(peer.transport()).isEqualTo("spool");
        assertThat(peer.destination()).isEqualTo("someone-else");
    }
}
