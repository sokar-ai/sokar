package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentDefinitionReader}.
 */
class AgentDefinitionReaderTest {

    private static final String MINIMAL = """
            name: example
            binary: example-cli

            git_identity:
              name: Example
              email: noreply@example.com

            headless:
              prompt_flag: "-p"
            """;

    private static AgentDefinition read(String yaml) {
        return AgentDefinitionReader.read(new StringReader(yaml), "test.yaml");
    }

    @Test
    void readsTheMinimumAnAgentMustDeclare() {

        final AgentDefinition definition = read(MINIMAL);

        assertThat(definition.name()).isEqualTo("example");
        assertThat(definition.binary()).isEqualTo("example-cli");
        assertThat(definition.gitIdentity().email()).isEqualTo("noreply@example.com");
        assertThat(definition.headless().promptFlag()).isEqualTo("-p");
    }

    @Test
    void labelDefaultsToTheName() {

        assertThat(read(MINIMAL).label()).isEqualTo("example");
        assertThat(read(MINIMAL + "label: Example Agent\n").label()).isEqualTo("Example Agent");
    }

    @Test
    void readsAnInstallBlockWrittenAsAYamlBlockScalar() {

        // Install fragments are shell. Writing them as a readable block beats a list of quoted
        // one-liners, so a block scalar is split into lines here.
        final AgentDefinition definition = read(MINIMAL + """
                install:
                  as_root: |
                    RUN mkdir -p /opt/example

                    RUN chown agent /opt/example
                  as_agent: |
                    RUN curl -fsSL https://example.com/install.sh | bash
                """);

        assertThat(definition.installAsRoot())
                .containsExactly("RUN mkdir -p /opt/example", "RUN chown agent /opt/example");
        assertThat(definition.installAsAgent()).hasSize(1);
    }

    @Test
    void readsTheDomainsAnAgentNeeds() {

        final AgentDefinition definition = read(MINIMAL + """
                allowed_domains:
                  - api.example.com
                  - registry.npmjs.org
                """);

        assertThat(definition.allowedDomains())
                .containsExactly("api.example.com", "registry.npmjs.org");
    }

    @Test
    void mapsCredentialTypesToEnvironmentVariables() {

        final AgentDefinition definition = read(MINIMAL + """
                provider:
                  token_env:
                    oauth: EXAMPLE_OAUTH_TOKEN
                    _default: EXAMPLE_API_KEY
                  base_url_env: EXAMPLE_BASE_URL
                """);

        assertThat(definition.tokenVariable("oauth")).isEqualTo("EXAMPLE_OAUTH_TOKEN");
        assertThat(definition.tokenVariable("api-key")).isEqualTo("EXAMPLE_API_KEY");
        assertThat(definition.baseUrlEnvironment()).isEqualTo("EXAMPLE_BASE_URL");
    }

    @Test
    void anAgentWithNoProviderTakesNoToken() {

        assertThat(read(MINIMAL).tokenVariable("oauth")).isNull();
    }

    @Test
    void defaultsToNoSessionSupport() {

        assertThat(read(MINIMAL).supportsResume()).isFalse();
        assertThat(read(MINIMAL).resumeFlag()).isNull();
    }

    @Test
    void refusesToSaySupportsResumeWithoutNamingTheFlag() {

        // Otherwise a resume request builds a command line with a null flag in it.
        assertThatThrownBy(() -> read(MINIMAL + "session:\n  supports_resume: true\n"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("supports resume but names no flag");
    }

    @Test
    void refusesAMissingSection() {

        assertThatThrownBy(() -> read("name: example\nbinary: x\n"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("no 'git_identity' section");
    }

    @Test
    void refusesANameThatWillNotSurviveTheLayersBelow() {

        assertThatThrownBy(() -> read(MINIMAL.replace("name: example", "name: Example Agent")))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Invalid agent name");
    }

    @Test
    void refusesDuplicateKeys() {

        assertThatThrownBy(() -> read(MINIMAL + "binary: something-else\n"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Cannot parse");
    }

    @Test
    void reportsTheOriginOfTheProblem() {

        assertThatThrownBy(() -> AgentDefinitionReader.read(new StringReader("[]"), "/x/claude.yaml"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("/x/claude.yaml");
    }
}
