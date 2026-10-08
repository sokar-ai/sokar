package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.AgentDefinitionReader;
import org.fuin.sokar.agent.api.AgentException;
import org.fuin.sokar.agent.api.ProviderDefinition;
import org.fuin.sokar.agent.api.ProviderDefinitionReader;
import org.fuin.sokar.agent.api.ProviderDirectory;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SelectedProvider}, and for the declarations actually shipped.
 */
class SelectedProviderTest {

    /** The declarations this repository installs, read from where they live rather than a copy. */
    private static final Path SHIPPED = Path.of("..", "..", "providers");

    private static Map<String, ProviderDefinition> shipped() {
        return new ProviderDirectory(java.util.List.of(SHIPPED)).all();
    }

    private static AgentDefinition agent(String providerBlock) {
        return AgentDefinitionReader.read(new java.io.StringReader("""
                name: example
                binary: example-cli
                git_identity:
                  name: Example
                  email: noreply@example.com
                headless:
                  prompt_flag: "-p"
                """ + providerBlock), "test.yaml");
    }

    @Test
    void aCredentialIsKeyedByTheProviderThatIssuedIt() {

        // The point of the change: two agents reaching one provider find one entry, rather than
        // each storing its own copy of the same secret under its own name.
        assertThat(SelectedProvider.credentialKey(java.util.Set.of("anthropic"), "one-agent",
                "anthropic")).isEqualTo("anthropic");
        assertThat(SelectedProvider.credentialKey(java.util.Set.of("anthropic"), "other-agent",
                "anthropic")).isEqualTo("anthropic");
    }

    @Test
    void aVaultWrittenBeforeTheChangeStillWorks() {

        // An entry under the agent's own name is what every existing vault holds. Upgrading must
        // not stop anyone authenticating.
        assertThat(SelectedProvider.credentialKey(java.util.Set.of("one-agent"), "one-agent",
                "anthropic")).isEqualTo("one-agent");
    }

    @Test
    void theProvidersNameWinsOnceItIsStored() {

        // Both present: the new key is the real one, so moving a credential takes effect without
        // having to remove the old entry first.
        assertThat(SelectedProvider.credentialKey(java.util.Set.of("one-agent", "anthropic"),
                "one-agent", "anthropic")).isEqualTo("anthropic");
    }

    @Test
    void anEmptyVaultIsToldWhereACredentialBelongs() {

        // Not the agent's name: this is what the "no credential for X" message says, and it
        // should name the place a new one goes.
        assertThat(SelectedProvider.credentialKey(java.util.Set.of(), "one-agent", "anthropic"))
                .isEqualTo("anthropic");
    }

    @Test
    void anAgentWithNoProviderKeepsItsOwnName() {

        assertThat(SelectedProvider.credentialKey(java.util.Set.of("direct"), "direct", null))
                .isEqualTo("direct");
    }

    @Test
    void theShippedDeclarationsParse() {

        // They are data files installed to /usr/share, so nothing else would catch a typo in
        // them until a task failed to start.
        assertThat(Files.isDirectory(SHIPPED)).isTrue();
        assertThat(shipped()).containsKeys("anthropic", "openrouter");
        assertThat(new ProviderDirectory(java.util.List.of(SHIPPED)).unreadable()).isEmpty();
    }

    @Test
    void twoAgentsReachTheSameProviderWithoutEitherDescribingIt() {

        // The whole point. Anthropic is declared once; an agent speaking its dialect and an
        // agent speaking whatever its provider speaks both reach it, and neither restates a
        // header, a prefix or a host.
        final AgentDefinition fixed = agent("""
                provider:
                  default: anthropic
                  dialect: anthropic-messages
                  socket_env: EXAMPLE_SOCKET
                """);
        final AgentDefinition agnostic = agent("""
                provider:
                  default: anthropic
                  dialect: native
                  endpoint: url
                """);

        final SelectedProvider one = SelectedProvider.choose(shipped(), fixed, null);
        final SelectedProvider two = SelectedProvider.choose(shipped(), agnostic, null);

        assertThat(one.route().upstreamHost()).isEqualTo("api.anthropic.com");
        assertThat(two.route().upstreamHost()).isEqualTo("api.anthropic.com");
        assertThat(one.route().authHeaderFor("api-key")).isEqualTo("x-api-key");
        assertThat(two.route().authHeaderFor("api-key")).isEqualTo("x-api-key");
    }

    @Test
    void theOperatorCanChooseADifferentProviderWithoutRebuildingAnything() {

        // What the same switch cost before this existed: five values in a definition and two
        // constants in Java, and a 15 MB binary rebuilt.
        final AgentDefinition agnostic = agent("""
                provider:
                  default: openrouter
                  dialect: native
                  endpoint: url
                """);

        assertThat(SelectedProvider.choose(shipped(), agnostic, null).name())
                .isEqualTo("openrouter");
        assertThat(SelectedProvider.choose(shipped(), agnostic, "anthropic").name())
                .isEqualTo("anthropic");
    }

    @Test
    void theDialectDecidesThePathTheAgentIsGiven() {

        final AgentDefinition openai = agent("""
                provider:
                  default: openrouter
                  dialect: openai
                  endpoint: url
                """);
        final AgentDefinition anthropicDialect = agent("""
                provider:
                  default: openrouter
                  dialect: anthropic-messages
                  endpoint: url
                """);

        assertThat(SelectedProvider.choose(shipped(), openai, null).route()
                .endpointFor("http://127.0.0.1:9419"))
                .isEqualTo("http://127.0.0.1:9419/api/v1");
        assertThat(SelectedProvider.choose(shipped(), anthropicDialect, null).route()
                .endpointFor("http://127.0.0.1:9419/"))
                .isEqualTo("http://127.0.0.1:9419/api");
    }

    @Test
    void refusesAPairingTheProviderCannotServe() {

        // Anthropic serves its own dialect only. An agent speaking the OpenAI one would
        // otherwise send a request that comes back as a confusing model error.
        final AgentDefinition openai = agent("""
                provider:
                  default: anthropic
                  dialect: openai
                  endpoint: url
                """);

        assertThatThrownBy(() -> SelectedProvider.choose(shipped(), openai, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does not serve");
    }

    @Test
    void namesAProviderThatIsNotDeclared() {

        final AgentDefinition agnostic = agent("""
                provider:
                  default: openrouter
                  dialect: native
                  endpoint: url
                """);

        assertThatThrownBy(() -> SelectedProvider.choose(shipped(), agnostic, "nowhere"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("No provider 'nowhere' is declared");
    }

    @Test
    void anAgentThatIsNotBrokeredSelectsNothing() {

        // Not an error: it takes its credential directly, and there is nothing to stand in for.
        assertThat(SelectedProvider.choose(shipped(), agent(""), null)).isNull();
    }

    @Test
    void anOperatorsOwnDeclarationWinsOverAPackagedOne(@org.junit.jupiter.api.io.TempDir Path own)
            throws Exception {

        Files.writeString(own.resolve("anthropic.yaml"), """
                name: anthropic
                upstream: https://api.anthropic.test
                dialects:
                  anthropic-messages: ""
                """);

        final var directory = new ProviderDirectory(java.util.List.of(own, SHIPPED));

        assertThat(directory.all().get("anthropic").upstreamHost())
                .isEqualTo("api.anthropic.test");
        assertThat(directory.all()).containsKey("openrouter");
    }

    @Test
    void oneBrokenDeclarationDoesNotHideTheRest(@org.junit.jupiter.api.io.TempDir Path own)
            throws Exception {

        Files.writeString(own.resolve("broken.yaml"), "name: [this is not a provider\n");

        final var directory = new ProviderDirectory(java.util.List.of(own, SHIPPED));

        assertThat(directory.all()).containsKeys("anthropic", "openrouter");
        assertThat(directory.unreadable()).hasSize(1);
    }

    @Test
    void aBrokenOperatorFileDoesNotMaskThePackagedOneOfTheSameName(
            @org.junit.jupiter.api.io.TempDir Path own) throws Exception {

        // The bug this test exists for: skipping a file must not also claim its name.
        Files.writeString(own.resolve("anthropic.yaml"), "name: [broken\n");

        final var directory = new ProviderDirectory(java.util.List.of(own, SHIPPED));

        assertThat(directory.all().get("anthropic")).isNotNull();
        assertThat(directory.all().get("anthropic").upstreamHost())
                .isEqualTo("api.anthropic.com");
    }

    @Test
    void considerSaysWhyRatherThanOnlyThat() {

        // The same three refusals choose() throws for, as values. Anything answering "could this
        // start" has to tell them apart, and reading them out of an exception's message would be
        // parsing prose for a decision - which is the one thing this contract refuses everywhere.
        final AgentDefinition noDefault = agent("""
                provider:
                  dialect: anthropic-messages
                  endpoint: socket
                  socket_env: EXAMPLE_SOCKET
                """);

        assertThat(SelectedProvider.consider(shipped(), noDefault, null).refusal())
                .isEqualTo(SelectedProvider.Refusal.NO_PROVIDER_CHOSEN);
        assertThat(SelectedProvider.consider(shipped(), noDefault, "nowhere").refusal())
                .isEqualTo(SelectedProvider.Refusal.UNKNOWN_PROVIDER);
    }

    @Test
    void considerNamesTheProviderItLookedFor() {

        // Which provider was wanted is what a person acts on, and it is not recoverable from the
        // refusal alone once several are declared.
        final AgentDefinition noDefault = agent("""
                provider:
                  dialect: anthropic-messages
                  endpoint: socket
                  socket_env: EXAMPLE_SOCKET
                """);

        assertThat(SelectedProvider.consider(shipped(), noDefault, "nowhere").wanted())
                .isEqualTo("nowhere");
    }

    @Test
    void considerAndChooseNeverDisagree() {

        // Two renderings of one decision. choose() throws where consider() refuses and returns
        // where it does not - if they ever drift, a preflight would pass and the run would fail.
        final AgentDefinition noDefault = agent("""
                provider:
                  dialect: anthropic-messages
                  endpoint: socket
                  socket_env: EXAMPLE_SOCKET
                """);
        for (final String requested : new String[] {null, "nowhere", "anthropic"}) {
            final SelectedProvider.Choice choice =
                    SelectedProvider.consider(shipped(), noDefault, requested);
            if (choice.refusal() == null) {
                assertThat(SelectedProvider.choose(shipped(), noDefault, requested))
                        .as("requested %s", requested).isNotNull();
            } else {
                assertThatThrownBy(() -> SelectedProvider.choose(shipped(), noDefault, requested))
                        .as("requested %s", requested)
                        .isInstanceOf(org.fuin.sokar.agent.api.AgentException.class)
                        .hasMessage(choice.detail());
            }
        }
    }
}
