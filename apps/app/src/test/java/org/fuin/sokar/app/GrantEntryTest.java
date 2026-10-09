package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.AgentDefinitionReader;
import org.fuin.sokar.agent.api.ProviderDefinition;
import org.fuin.sokar.agent.api.ProviderDefinitionReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GrantEntry}: the entry {@code sokar vault authorize} makes for a provider a person has not stored
 * anything for yet, from the provider's grant and the client id of the agent that signs in.
 */
class GrantEntryTest {

    private static final ProviderDefinition COPILOT = ProviderDefinitionReader.read(new StringReader("""
            name: github-copilot
            upstream: https://api.githubcopilot.com
            dialects:
              openai: ""
            grant:
              kind: oauth-device
              device_authorization_url: https://github.com/login/device/code
              token_url: https://github.com/login/oauth/access_token
              scopes: read:user
            """), "github-copilot.yaml");

    private static final ProviderDefinition OPENROUTER = ProviderDefinitionReader.read(new StringReader("""
            name: openrouter
            upstream: https://openrouter.ai
            dialects:
              openai: "/api/v1"
            """), "openrouter.yaml");

    private static AgentDefinition agent(final String name, final String grants) {
        return AgentDefinitionReader.read(new StringReader("""
                name: %s
                binary: %s
                git_identity:
                  name: X
                  email: x@example.com
                headless:
                  prompt_flag: "-p"
                %s""".formatted(name, name, grants)), name + ".yaml");
    }

    private static final String SIGNS_IN = """
            login:
              grants:
                github-copilot:
                  hosts:
                    github.com: Ov23-github
                    "*": Ov23-enterprise
                  owner: another tool's app
            """;

    @Test
    void makesTheEntryFromTheProvidersGrantAndTheAgentsClientId() {
        final GrantEntry.Choice choice = GrantEntry.choose(COPILOT, List.of(agent("omp", SIGNS_IN)), null);

        assertThat(choice.refusal()).isNull();
        assertThat(choice.agent()).isEqualTo("omp");
        assertThat(choice.entry().type()).isEqualTo("oauth-device");
        assertThat(choice.entry().settings()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "client_id", "Ov23-github",
                "device_authorization_url", "https://github.com/login/device/code",
                "token_url", "https://github.com/login/oauth/access_token",
                "scopes", "read:user",
                "client_owner", "another tool's app"));
    }

    @Test
    void refusesAProviderThatIsGrantedNothing() {
        // A key is stored with 'vault put'; there is nothing to authorize.
        assertThat(GrantEntry.choose(OPENROUTER, List.of(agent("omp", SIGNS_IN)), null).refusal())
                .contains("vault put");
    }

    @Test
    void refusesWhenNoInstalledAgentSignsInToIt() {
        assertThat(GrantEntry.choose(COPILOT, List.of(agent("claude", "")), null).refusal())
                .contains("no installed agent").contains("github-copilot");
    }

    @Test
    void asksWhichAgentWhenSeveralSignInAndTakesTheOneNamed() {
        final List<AgentDefinition> two = List.of(agent("omp", SIGNS_IN), agent("pi", SIGNS_IN));

        assertThat(GrantEntry.choose(COPILOT, two, null).refusal()).contains("--agent").contains("omp").contains("pi");
        assertThat(GrantEntry.choose(COPILOT, two, "pi").agent()).isEqualTo("pi");
        assertThat(GrantEntry.choose(COPILOT, two, "claude").refusal()).contains("claude");
    }
}
