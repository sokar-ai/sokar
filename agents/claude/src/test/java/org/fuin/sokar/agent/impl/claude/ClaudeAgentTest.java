package org.fuin.sokar.agent.impl.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.agent.api.Agent;
import org.fuin.sokar.agent.api.AgentException;
import org.fuin.sokar.agent.api.AgentRegistry;
import org.fuin.sokar.agent.api.ProviderDefinition;
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
        // api.anthropic.com is deliberately NOT here: the provider declares its own host, and
        // this agent no longer restates it.
        assertThat(agent.definition().allowedDomains())
                .contains("platform.claude.com")
                .doesNotContain("api.anthropic.com");
    }

    @Test
    void takesItsCredentialVariablesFromTheProviderRatherThanRestatingThem() {

        // Sending an OAuth token as an API key fails in a way that looks like a bad key, so the
        // two variables have to differ - but which they are is Anthropic's fact, not this
        // agent's, and every agent reaching Anthropic would otherwise repeat it.
        assertThat(agent.definition().tokenVariable("oauth")).isNull();
        assertThat(agent.definition().tokenVariable("api-key")).isNull();

        final ProviderDefinition anthropic = new ProviderDefinition("anthropic", "Anthropic",
                "https://api.anthropic.com", java.util.Map.of("anthropic-messages", ""),
                java.util.Map.of("oauth", "Authorization", "_default", "x-api-key"),
                java.util.Map.of("oauth", "Bearer ", "_default", ""), java.util.Map.of(),
                java.util.Map.of("oauth", "CLAUDE_CODE_OAUTH_TOKEN",
                        "_default", "ANTHROPIC_API_KEY"));

        assertThat(agent.definition().tokenVariable("oauth", anthropic))
                .isEqualTo("CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(agent.definition().tokenVariable("api-key", anthropic))
                .isEqualTo("ANTHROPIC_API_KEY");
    }

    @Test
    void declaresHowItMustBePointedAtAProvider() {

        assertThat(agent.definition().provider()).isNotNull();
        assertThat(agent.definition().provider().dialect()).isEqualTo("anthropic-messages");
        assertThat(agent.definition().provider().defaultProvider()).isEqualTo("anthropic");
        assertThat(agent.definition().provider().socketEnvironment())
                .isEqualTo("ANTHROPIC_UNIX_SOCKET");
    }

    @Test
    void buildsAHeadlessCommandFromTheSharedBuilder() {

        assertThat(agent.headlessCommand(
                new RunRequest("fix the bug", "sonnet", Integer.valueOf(3), null, false, true)))
                .containsExactly("claude", "--model", "sonnet", "--max-turns", "3",
                        // --verbose is part of what machine-readable output costs for Claude:
                        // it refuses stream-json in print mode without it. Found by running it.
                        "--output-format", "stream-json", "--verbose", "-p", "fix the bug");
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
    void installsAPinnedAndVerifiedBinary() {

        // Not 'curl | bash'. The version and digest are what make an image build reproducible
        // and make "which version ran" answerable from the definition.
        final var definition = agent.definition();

        assertThat(definition.version()).isEqualTo("2.1.236");
        assertThat(definition.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.url()).startsWith("https://downloads.claude.ai/");
            assertThat(artifact.url()).contains(definition.version());
            assertThat(artifact.sha256()).matches("[a-f0-9]{64}");
            assertThat(artifact.unverified()).isFalse();
            assertThat(artifact.target()).isEqualTo("/home/agent/.local/bin/claude");
        });
        assertThat(definition.unverifiedArtifacts()).isEmpty();
    }

    @Test
    void contributesAnImageLayerThatChecksBeforeItInstalls() {

        final var lines = agent.imageLayer().asAgent();

        assertThat(agent.imageLayer().isEmpty()).isFalse();
        assertThat(lines).anyMatch(line -> line.contains("sha256sum -c -"));

        final int check = indexOf(lines, "sha256sum -c -");
        final int install = indexOf(lines, "install -D");
        assertThat(install).isGreaterThan(check);
    }

    private static int indexOf(java.util.List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }
}
