package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentDescriptionTest {

    /** The fields tier 1 reads, as an agent prints them - trimmed from a real {@code describe}. */
    private static final String DESCRIBED = """
            {"definition":{"name":"a","binary":"a-cli","headless":{"promptFlag":"-p"},
            "provider":{"default":"anthropic","endpoint":"socket","socketEnvironment":"A_SOCKET",
            "baseUrlEnvironment":"A_BASE_URL"},"allowedDomains":["example.com","example.org"],
            "version":"1.0.0"},"protocolVersion":3}""";

    @Test
    void readsWhatTierOneNeeds() {
        final AgentDescription description = AgentDescription.parse(DESCRIBED);

        assertThat(description).isEqualTo(new AgentDescription("1.0.0", List.of("example.com", "example.org"),
                "a-cli", "-p", "anthropic", "A_SOCKET", "A_BASE_URL", "socket"));
    }

    @Test
    void readsAnAgentWithNoProviderAndNoPromptFlagAsEmpty() {
        final AgentDescription description = AgentDescription.parse("""
                {"definition":{"binary":"b-cli","headless":{},"allowedDomains":[],"version":"2"}}""");

        assertThat(description.promptFlag()).isEmpty();
        assertThat(description.provider()).isEmpty();
        assertThat(description.socketEnvironment()).isEmpty();
        // Said nothing about its endpoint, so it is addressed by socket - the script's own default.
        assertThat(description.endpoint()).isEqualTo("socket");
    }

    @Test
    void readsAnAgentThatCanOnlyBeGivenAUrl() {
        assertThat(AgentDescription.parse(DESCRIBED.replace("\"endpoint\":\"socket\"", "\"endpoint\":\"url\""))
                .endpoint()).isEqualTo("url");
    }

    @Test
    void refusesADescriptionWithoutTheFieldsEveryAgentHas() {
        assertThatThrownBy(() -> AgentDescription.parse(DESCRIBED.replace("\"version\":\"1.0.0\"", "\"v\":1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no version");
        assertThatThrownBy(() -> AgentDescription.parse("usage: describe"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an agent description");
    }

    @Test
    void handsTheScriptOneLineOfQuotedAssignments() {
        final String line = Leg.assignments("a", AgentDescription.parse(DESCRIBED));

        // Two domains on one variable, one per line, as the script splits them.
        assertThat(line).startsWith("SOKAR_E2E_AGENT='a' SOKAR_E2E_CLI_VERSION='1.0.0' ")
                .contains("SOKAR_E2E_DOMAINS='example.com\nexample.org' ")
                .contains("SOKAR_E2E_PROMPT_FLAG='-p' ").endsWith("SOKAR_E2E_ENDPOINT='socket' ");
    }

    @Test
    void findsTheAgentBinaryAmongWhatTheModulesLeftInTarget() {
        // What a leg found on 2026-09-28: the agent API's jars and bill share the prefix.
        final List<String> found = List.of(
                "/home/build/sokar/agents/api/target/sokar-agent-api-0.1.0-SNAPSHOT-cyclonedx.json",
                "/home/build/sokar/agents/api/target/sokar-agent-api-0.1.0-SNAPSHOT.jar",
                "/home/build/sokar/agents/api/target/sokar-agent-api-0.1.0-SNAPSHOT-sources.jar",
                "/home/build/sokar/agents/one/target/sokar-agent-one-0.1.0-SNAPSHOT.jar",
                "/home/build/sokar/agents/one/target/sokar-agent-one",
                "/home/build/sokar/agents/one/target/sokar-agent-one-0.1.0-SNAPSHOT-cyclonedx.json");

        assertThat(Leg.agentBinaries(found)).containsExactly("/home/build/sokar/agents/one/target/sokar-agent-one");
    }
}
