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
}
