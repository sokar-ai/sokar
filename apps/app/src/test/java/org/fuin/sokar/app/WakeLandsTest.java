package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.AgentDefinitionReader;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.runtime.Podman;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a wake types only into rest that holds, and that its line leaves the agent's input box: pressed once more
 * when it stands there, and said as not delivered when it still does.
 */
class WakeLandsTest {

    @TempDir
    private Path dir;

    private static final AgentDefinition CLAUDE = AgentDefinitionReader.read(new StringReader("""
            name: claude
            binary: claude
            git_identity: { name: C, email: c@example.com }
            headless: { prompt_flag: "-p" }
            session:
              at_rest:
                shows: ["bypass permissions on"]
                lacks: ["esc to interrupt", "Esc to cancel"]
              waiting:
                screen:
                  - { contains: "Esc to cancel", for: "a choice" }
            """), "claude.yaml");

    private static final String REST = ">\n  bypass permissions on (shift+tab)";

    private static final String WORK = "* Thinking (esc to interrupt)\n  bypass permissions on (shift+tab)";

    /** Screens shown in turn, the last one again once they run out; and what was sent. */
    private static final class Stand implements AgentWake.Terminal {
        private final Deque<String> screens;
        private final List<String> sent = new ArrayList<>();

        Stand(final String... screens) {
            this.screens = new ArrayDeque<>(List.of(screens));
        }

        @Override
        public Podman.Screen screen(final String container) {
            final String shown = screens.size() > 1 ? screens.poll() : screens.peek();
            return new Podman.Screen(List.of(shown.split("\n")), true);
        }

        @Override
        public boolean type(final String container, final String line) {
            sent.add("type");
            return true;
        }

        @Override
        public boolean enter(final String container) {
            sent.add("enter");
            return true;
        }
    }

    private AgentWake waker(final Stand stand) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new AgentWake(new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0), Duration.ZERO, stand);
    }

    @Test
    void restForOneLookOnlyIsNotTypedInto() {
        // An agent's work line comes and goes between its tool calls: one look fell into such a gap and the wake line
        // broke into a turn that was still running.
        final Stand stand = new Stand(REST, WORK);

        assertThat(waker(stand).wake("sokar-p-writer", CLAUDE, AgentWake.LINE)).isFalse();
        assertThat(stand.sent).isEmpty();
    }

    @Test
    void restThatHoldsIsTypedIntoOnceAndTheAgentTakingItIsDelivered() {
        final Stand stand = new Stand(REST, REST, WORK);

        assertThat(waker(stand).wake("sokar-p-writer", CLAUDE, AgentWake.LINE)).isTrue();
        assertThat(stand.sent).containsExactly("type");
    }

    @Test
    void aLineLeftInTheInputBoxIsSubmittedOnceMore() {
        // Claude Code drew a notice and took the Enter: the line stood in the box and the agent never worked.
        final Stand stand = new Stand(REST, REST, REST, WORK);

        assertThat(waker(stand).wake("sokar-p-writer", CLAUDE, AgentWake.LINE)).isTrue();
        assertThat(stand.sent).containsExactly("type", "enter");
    }

    @Test
    void aLineThatStillStandsIsNotDeliveredAndIsSubmittedNotTypedAgainNextTime() {
        final Stand stuck = new Stand(REST);
        final AgentWake waker = waker(stuck);

        assertThat(waker.wake("sokar-p-writer", CLAUDE, AgentWake.LINE)).as("never said as delivered").isFalse();
        assertThat(stuck.sent).containsExactly("type", "enter");

        final Stand later = new Stand(REST, REST, WORK);
        final AgentWake again = waker(later);
        assertThat(again.wake("sokar-p-writer", CLAUDE, AgentWake.LINE)).isTrue();
        assertThat(later.sent).as("the line is in the box already: only Enter").containsExactly("enter");
    }
}
