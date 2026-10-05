package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for what an agent answers about single lines of its output: how they read, and whether one is its run's end.
 */
class AgentServerLinesTest {

    private static final Agent AGENT = new Agent() {
        @Override
        public AgentDefinition definition() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String name() {
            return "lines";
        }

        @Override
        public LogFormatter logFormatter() {
            return line -> line.startsWith("#") ? null : line.toUpperCase(java.util.Locale.ROOT);
        }

        @Override
        public AgentEnd ended(String line) {
            return line.equals("END") ? new AgentEnd(false, "402 more credits", AgentEnd.PROVIDER, 402) : null;
        }
    };

    @Test
    void linesAreFormattedInPlaceAndAHiddenOneAnswersNothing(@TempDir Path dir) throws Exception {

        // The log's lines were formatted only from its whole file: 53 MB read for one update of a small console.
        try (AgentServer server = new AgentServer(AGENT, dir.resolve("agent.sock"))) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(dir.resolve("agent.sock"))) {
                final Map<String, Object> formatted = client.call(AgentProtocol.FORMAT,
                        Map.of("lines", List.of("a tool call", "# noise", "done")));
                assertThat(formatted.get("lines")).isEqualTo(Arrays.asList("A TOOL CALL", null, "DONE"));

                assertThat(AgentEnd.of((Map<?, ?>) client.call(AgentProtocol.ENDED, Map.of("line", "END")).get("ended")))
                        .isEqualTo(new AgentEnd(false, "402 more credits", AgentEnd.PROVIDER, 402));
                assertThat(client.call(AgentProtocol.ENDED, Map.of("line", "working"))).doesNotContainKey("ended");
            }
        }
    }
}
