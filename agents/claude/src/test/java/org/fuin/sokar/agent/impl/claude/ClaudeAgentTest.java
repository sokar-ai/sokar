package org.fuin.sokar.agent.impl.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.agent.api.Agent;
import org.fuin.sokar.agent.api.AgentException;
import org.fuin.sokar.agent.api.AgentRegistry;
import org.fuin.sokar.agent.api.RunRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Claude agent module.
 */
class ClaudeAgentTest {

    private final Agent agent = new ClaudeAgent();

    @Test
    void isDiscoveredThroughTheServiceLoader() {

        // Nothing names ClaudeAgent for this to work: it is found through
        // META-INF/services, which is what lets Sokar stay ignorant of it.
        final AgentRegistry registry = AgentRegistry.discover();

        assertThat(registry.names()).contains("claude");
        assertThat(registry.require("claude").definition().binary()).isEqualTo("claude");
    }

    @Test
    void readsItsOwnDefinition() {

        assertThat(agent.definition().label()).isEqualTo("Claude Code");
        assertThat(agent.definition().gitIdentity().email()).isEqualTo("noreply@anthropic.com");
        assertThat(agent.definition().allowedDomains()).contains("api.anthropic.com");
    }

    @Test
    void sendsAnOauthTokenAndAnApiKeyToDifferentVariables() {

        // Sending one as the other fails in a way that looks like a bad key.
        assertThat(agent.definition().tokenVariable("oauth")).isEqualTo("CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(agent.definition().tokenVariable("api-key")).isEqualTo("ANTHROPIC_API_KEY");
    }

    @Test
    void buildsAHeadlessCommandFromTheSharedBuilder() {

        assertThat(agent.headlessCommand(
                new RunRequest("fix the bug", "sonnet", Integer.valueOf(3), null, false, true)))
                .containsExactly("claude", "--model", "sonnet", "--max-turns", "3",
                        "--output-format", "stream-json", "-p", "fix the bug");
    }

    @Test
    void extractsAnOauthTokenWithItsRefreshAndExpiry(@TempDir Path dir) throws IOException {

        Files.writeString(dir.resolve(".credentials.json"), """
                {"claudeAiOauth":{"accessToken":"sk-ant-oat-xyz",
                 "refreshToken":"sk-ant-ort-abc","expiresAt":1788000000}}
                """);

        assertThat(agent.credentialExtractor().extract(dir)).hasValueSatisfying(credential -> {
            assertThat(credential.type()).isEqualTo("oauth");
            assertThat(credential.secret()).isEqualTo("sk-ant-oat-xyz");
            assertThat(credential.attributes()).containsEntry("refreshToken", "sk-ant-ort-abc");
            assertThat(credential.attributes()).containsEntry("expiresAt", "1788000000");
        });
    }

    @Test
    void extractsAnApiKey(@TempDir Path dir) throws IOException {

        Files.writeString(dir.resolve(".credentials.json"), "{\"apiKey\":\"sk-ant-api-123\"}");

        assertThat(agent.credentialExtractor().extract(dir)).hasValueSatisfying(credential ->
                assertThat(credential.type()).isEqualTo("api-key"));
    }

    @Test
    void reportsNotLoggedInAsEmpty(@TempDir Path dir) {

        assertThat(agent.credentialExtractor().extract(dir)).isEmpty();
    }

    @Test
    void refusesAFileThatHoldsNeither(@TempDir Path dir) throws IOException {

        // Reporting "not logged in" here would send the operator to run a login that has already
        // succeeded.
        Files.writeString(dir.resolve(".credentials.json"), "{\"somethingElse\":true}");

        assertThatThrownBy(() -> agent.credentialExtractor().extract(dir))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("neither an OAuth token nor an API key");
    }

    @Test
    void keepsTheCredentialOutOfItsOwnToString() {

        assertThat(org.fuin.sokar.agent.api.Credential.of("oauth", "sk-ant-secret").toString())
                .doesNotContain("sk-ant-secret");
    }

    @Test
    void rendersStreamJsonAsReadableLines() {

        final var formatter = agent.logFormatter();

        assertThat(formatter.format(
                "{\"type\":\"assistant\",\"message\":{\"content\":[{\"text\":\"Looking at it.\"}]}}"))
                .isEqualTo("Looking at it.");
        assertThat(formatter.format(
                "{\"type\":\"assistant\",\"message\":{\"content\":"
                        + "[{\"type\":\"tool_use\",\"name\":\"Bash\"}]}}"))
                .isEqualTo("[Bash]");
        assertThat(formatter.format("{\"type\":\"result\",\"num_turns\":4,\"total_cost_usd\":0.12}"))
                .isEqualTo("-- done, 4 turns, $0.12");
        assertThat(formatter.format("{\"type\":\"system\",\"subtype\":\"init\"}")).isNull();
    }

    @Test
    void showsAnythingItDoesNotUnderstand() {

        // A viewer that swallowed unrecognised lines would hide exactly the output worth reading
        // when something has gone wrong.
        final var formatter = agent.logFormatter();

        assertThat(formatter.format("plain text from a crash")).isEqualTo("plain text from a crash");
        assertThat(formatter.format("{not valid json")).isEqualTo("{not valid json");
        assertThat(formatter.format("{\"type\":\"something_new\"}")).isEqualTo("{\"type\":\"something_new\"}");
    }

    @Test
    void contributesAnImageLayer() {

        assertThat(agent.imageLayer().asAgent())
                .anyMatch(line -> line.contains("claude.ai/install.sh"));
        assertThat(agent.imageLayer().isEmpty()).isFalse();
    }
}
