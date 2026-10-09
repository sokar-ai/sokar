package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProviderDefinitionReader} and {@link ProviderDefinition}.
 */
class ProviderDefinitionReaderTest {

    private static final String OPENROUTER = """
            name: openrouter
            label: OpenRouter
            upstream: https://openrouter.ai
            dialects:
              openai: "/api/v1"
              anthropic-messages: "/api"
            auth_header:
              _default: Authorization
            auth_prefix:
              _default: "Bearer "
            token_env:
              _default: OPENROUTER_API_KEY
            """;

    private static ProviderDefinition read(String yaml) {
        return ProviderDefinitionReader.read(new StringReader(yaml), "test.yaml");
    }

    @Test
    void readsWhatAProviderDeclares() {

        final ProviderDefinition provider = read(OPENROUTER);

        assertThat(provider.name()).isEqualTo("openrouter");
        assertThat(provider.label()).isEqualTo("OpenRouter");
        assertThat(provider.upstreamHost()).isEqualTo("openrouter.ai");
        assertThat(provider.authHeaderFor("api-key")).isEqualTo("Authorization");
        assertThat(provider.authPrefixFor("api-key")).isEqualTo("Bearer ");
        assertThat(provider.tokenVariable("api-key")).isEqualTo("OPENROUTER_API_KEY");
    }

    @Test
    void theFirstDialectDeclaredIsTheOneTheProviderIs() {

        // Order is meaning here, so the reader must not use a map that loses it. An agent asking
        // for 'native' gets whichever comes first, and picking the wrong one sends the request in
        // a format the provider does not serve at that path.
        assertThat(read(OPENROUTER).nativeDialect()).isEqualTo("openai");
        assertThat(read(OPENROUTER).pathFor(ProviderDefinition.NATIVE_DIALECT))
                .isEqualTo("/api/v1");
    }

    @Test
    void servesEachDialectUnderItsOwnPath() {

        // The same provider, two wire formats, two paths. A base URL missing the path answers
        // "model not found" rather than 404, which reads as a bad model name.
        assertThat(read(OPENROUTER).pathFor("openai")).isEqualTo("/api/v1");
        assertThat(read(OPENROUTER).pathFor("anthropic-messages")).isEqualTo("/api");
    }

    @Test
    void aProviderMayServeItsOwnDialectAtTheRoot() {

        final ProviderDefinition anthropic = read("""
                name: anthropic
                upstream: https://api.anthropic.com
                dialects:
                  anthropic-messages: ""
                """);

        assertThat(anthropic.pathFor("anthropic-messages")).isEmpty();
        assertThat(anthropic.label()).isEqualTo("anthropic");
    }

    @Test
    void saysWhichDialectsItActuallyServes() {

        assertThat(read(OPENROUTER).serves("openai")).isTrue();
        assertThat(read(OPENROUTER).serves("responses")).isFalse();
        assertThat(read(OPENROUTER).serves(ProviderDefinition.NATIVE_DIALECT)).isTrue();
    }

    @Test
    void refusesADialectItDoesNotServe() {

        // Named rather than defaulted: a silent fallback would send the request in a format the
        // provider does not answer, and the failure would surface as a model error.
        assertThatThrownBy(() -> read(OPENROUTER).pathFor("responses"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does not serve 'responses'");
    }

    @Test
    void refusesAnUpstreamThatWouldSendTheCredentialInTheClear() {

        assertThatThrownBy(() -> read("""
                name: example
                upstream: http://api.example.com
                dialects:
                  openai: ""
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("must be an https URL");
    }

    @Test
    void refusesAProviderThatServesNothing() {

        // It could never be selected, so a declaration without dialects is a mistake rather
        // than a choice.
        assertThatThrownBy(() -> read("""
                name: example
                upstream: https://api.example.com
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("declares no dialects");
    }

    @Test
    void refusesToLetAProviderCallADialectNative() {

        // 'native' is how an agent asks for whichever comes first, so a provider offering one by
        // that name would be unreachable through the very word meant to reach it.
        assertThatThrownBy(() -> read("""
                name: example
                upstream: https://api.example.com
                dialects:
                  native: "/v1"
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("which is the name an agent uses");
    }

    @Test
    void refusesANameThatWillNotSurviveTheLayersBelow() {

        assertThatThrownBy(() -> read("""
                name: Open Router
                upstream: https://openrouter.ai
                dialects:
                  openai: ""
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Invalid provider name");
    }

    @Test
    void readsAKeyThatBelongsInTheUrl() {

        // A service that takes its key as ?key=... declares the parameter, and then no header.
        final ProviderDefinition provider = read("""
                name: gemini
                upstream: https://generativelanguage.googleapis.com
                dialects:
                  gemini: ""
                auth_query:
                  _default: key
                """);

        assertThat(provider.authQueryFor("api-key")).isEqualTo("key");
        assertThat(read(OPENROUTER).authQueryFor("api-key")).as("a provider that declares none").isNull();
    }

    @Test
    void everyShippedProviderReadsAndCopilotIsReachedAtItsRootWithABearer() throws java.io.IOException {
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(
                java.nio.file.Path.of("..", "..", "providers"))) {
            for (final java.nio.file.Path file : files.filter(each -> each.toString().endsWith(".yaml")).toList()) {
                assertThat(ProviderDefinitionReader.fromFile(file).name()).as(file.toString())
                        .isEqualTo(file.getFileName().toString().replace(".yaml", ""));
            }
        }

        final ProviderDefinition copilot = ProviderDefinitionReader.fromFile(
                java.nio.file.Path.of("..", "..", "providers", "github-copilot.yaml"));

        // Oh My Pi puts every Copilot path under one base, and Copilot serves them all at its root.
        assertThat(copilot.pathFor(ProviderDefinition.NATIVE_DIALECT)).isEmpty();
        assertThat(copilot.upstreamHost()).isEqualTo("api.githubcopilot.com");
        assertThat(copilot.authHeaderFor("oauth-device")).isEqualTo("Authorization");
        assertThat(copilot.authPrefixFor("oauth-device")).isEqualTo("Bearer ");
        // Without a variable to put the task's token in, nothing was brokered and the agent had no key.
        assertThat(copilot.tokenVariable("oauth-device")).isEqualTo("COPILOT_GITHUB_TOKEN");
    }

    @org.junit.jupiter.api.Test
    void aProviderDeclaredTwiceSaysWhichFileWonAndWhichWasSetAside(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {

        // The first file to name a provider won, whatever it was called, and nothing said another was set aside: a file
        // dropped in as 'experiment.yaml' took 'openrouter' over, and with it where the stored key goes.
        final java.nio.file.Path own = java.nio.file.Files.createDirectories(dir.resolve("own"));
        final java.nio.file.Path packaged = java.nio.file.Files.createDirectories(dir.resolve("packaged"));
        java.nio.file.Files.writeString(own.resolve("experiment.yaml"),
                OPENROUTER.replace("https://openrouter.ai", "https://other.example"));
        java.nio.file.Files.writeString(packaged.resolve("openrouter.yaml"), OPENROUTER);

        final ProviderDirectory directory = new ProviderDirectory(java.util.List.of(own, packaged));

        assertThat(directory.all().get("openrouter").upstream()).isEqualTo("https://other.example");
        assertThat(directory.shadowed()).containsEntry(packaged.resolve("openrouter.yaml"), own.resolve("experiment.yaml"));
    }

    @Test
    void readsTheGrantAPersonGivesOnceWithTheSettingsEveryoneShares() {
        // GitHub's device flow is the same for everybody but the client: the URLs and the scope belong to the provider,
        // the client id to the agent that signs in, so a person types none of them.
        final ProviderDefinition copilot = read("""
                name: github-copilot
                upstream: https://api.githubcopilot.com
                dialects:
                  openai: ""
                grant:
                  kind: oauth-device
                  device_authorization_url: https://github.com/login/device/code
                  token_url: https://github.com/login/oauth/access_token
                  scopes: read:user
                """);

        assertThat(copilot.grant()).containsEntry("kind", "oauth-device")
                .containsEntry("device_authorization_url", "https://github.com/login/device/code")
                .containsEntry("token_url", "https://github.com/login/oauth/access_token")
                .containsEntry("scopes", "read:user")
                .doesNotContainKey("client_id");
        assertThat(read(OPENROUTER).grant()).as("a key, nothing to grant").isEmpty();
    }
}
