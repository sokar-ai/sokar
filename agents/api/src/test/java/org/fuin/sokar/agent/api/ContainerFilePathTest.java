package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * What an agent may name as a place to put its own configuration.
 * <p>
 * This is where an agent's answer becomes Sokar's instruction, so it is where the answer has to
 * stop being taken on trust.
 */
class ContainerFilePathTest {

    @Test
    void anOrdinaryConfigurationPathIsAccepted() {
        assertThat(new ContainerFile("/home/agent/.claude/settings.json", "{}", false).path())
                .isEqualTo("/home/agent/.claude/settings.json");
    }

    @Test
    void aQuoteIsRefused() {

        // It used to reach a shell command built by concatenating the path into single quotes,
        // where one quote ends the quoting and the remainder becomes commands - run inside the
        // container as the agent user. The shell is gone; a path with a quote in it was never a
        // path anybody meant, so it stays refused at the boundary rather than only made harmless
        // at the far end.
        assertThatThrownBy(() -> new ContainerFile("/home/agent/'; id; '", "x", false))
                .isInstanceOf(AgentException.class).hasMessageContaining("quotes");
        assertThatThrownBy(() -> new ContainerFile("/home/agent/\"x\"", "x", false))
                .isInstanceOf(AgentException.class).hasMessageContaining("quotes");
        assertThatThrownBy(() -> new ContainerFile("/home/agent/x\\y", "x", false))
                .isInstanceOf(AgentException.class).hasMessageContaining("quotes");
    }

    @Test
    void aNewlineIsRefused() {
        assertThatThrownBy(() -> new ContainerFile("/home/agent/x\nid", "x", false))
                .isInstanceOf(AgentException.class).hasMessageContaining("control characters");
    }

    @Test
    void climbingOutIsRefused() {

        // A file placed relative to a directory it is not under is a file placed somewhere nobody
        // asked for, whatever the first character says.
        assertThatThrownBy(() -> new ContainerFile("/home/agent/../../etc/passwd", "x", false))
                .isInstanceOf(AgentException.class).hasMessageContaining("'..'");
        assertThatThrownBy(() -> new ContainerFile("/home/agent/..", "x", false))
                .isInstanceOf(AgentException.class).hasMessageContaining("'..'");
    }

    @Test
    void aPathThatNamesNoFileIsRefused() {
        assertThatThrownBy(() -> new ContainerFile("/home/agent/", "x", false))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new ContainerFile("/home//agent/x", "x", false))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void aRelativePathIsStillRefused() {
        assertThatThrownBy(() -> new ContainerFile("home/agent/x", "x", false))
                .isInstanceOf(AgentException.class).hasMessageContaining("absolute");
    }
}
