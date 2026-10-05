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
    void readsWhatTheAgentSaysAboutProviders() {

        final AgentDefinition definition = read(MINIMAL + """

                provider:
                  default: example
                  dialect: openai
                  endpoint: socket
                  socket_env: EXAMPLE_UNIX_SOCKET
                  base_url_env: EXAMPLE_BASE_URL
                  token_env:
                    oauth: EXAMPLE_OAUTH_TOKEN
                """);

        assertThat(definition.provider()).isNotNull();
        assertThat(definition.provider().dialect()).isEqualTo("openai");
        assertThat(definition.provider().defaultProvider()).isEqualTo("example");
        assertThat(definition.provider().socketEnvironment()).isEqualTo("EXAMPLE_UNIX_SOCKET");
        assertThat(definition.provider().baseUrlEnvironment()).isEqualTo("EXAMPLE_BASE_URL");

        // The agent's own override still works; the fallback lives on the provider now.
        assertThat(definition.tokenVariable("oauth")).isEqualTo("EXAMPLE_OAUTH_TOKEN");
    }

    @Test
    void anAgentThatCannotBeRedirectedDeclaresNoProvider() {

        // Not an error. It means the agent must be given its credential directly, which is
        // the operator's decision to make rather than the reader's to paper over.
        assertThat(read(MINIMAL).provider()).isNull();
    }

    @Test
    void readsTheDomainsAnAgentIsDeliberatelyDenied() {

        // Telemetry hosts are declared, not merely left out: the firewall cannot tell an
        // oversight from a policy, and the domain-coverage test needs to.
        final AgentDefinition definition = read(MINIMAL + """
                allowed_domains:
                  - api.example.com
                refused_domains:
                  - telemetry.example.com
                """);

        assertThat(definition.allowedDomains()).containsExactly("api.example.com");
        assertThat(definition.refusedDomains()).containsExactly("telemetry.example.com");
    }

    @Test
    void refusesADomainThatIsBothAllowedAndRefused() {

        // Which list wins would otherwise depend on which one the ruleset consults first.
        assertThatThrownBy(() -> read(MINIMAL + """
                allowed_domains:
                  - api.example.com
                refused_domains:
                  - api.example.com
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("both allows and refuses")
                .hasMessageContaining("api.example.com");
    }

    @Test
    void refusingNothingIsTheNormalCase() {

        assertThat(read(MINIMAL).refusedDomains()).isEmpty();
    }

    @Test
    void mapsCredentialTypesToEnvironmentVariables() {

        final AgentDefinition definition = read(MINIMAL + """
                provider:
                  dialect: openai
                  base_url_env: EXAMPLE_BASE_URL
                  token_env:
                    oauth: EXAMPLE_OAUTH_TOKEN
                    _default: EXAMPLE_API_KEY
                """);

        assertThat(definition.tokenVariable("oauth")).isEqualTo("EXAMPLE_OAUTH_TOKEN");
        assertThat(definition.tokenVariable("api-key")).isEqualTo("EXAMPLE_API_KEY");
        assertThat(definition.provider().baseUrlEnvironment()).isEqualTo("EXAMPLE_BASE_URL");
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

    @Test
    void readsWhatAnAgentShipsInItsOwnPackage() {

        // The alternative to a pinned URL, for a tool that is a tree of files rather than one
        // binary: the package carries it and the image build fetches nothing.
        final AgentDefinition definition = read(MINIMAL + """
                install:
                  packaged:
                    - source: /usr/share/sokar/agents/uc
                      target: /opt/uc
                """);

        assertThat(definition.packaged()).hasSize(1);
        assertThat(definition.packaged().getFirst().source())
                .isEqualTo("/usr/share/sokar/agents/uc");
        assertThat(definition.packaged().getFirst().target()).isEqualTo("/opt/uc");
    }

    @Test
    void readsARelativePackagedSourceAsBesideTheAgent() {

        // Resolved beside the agent's binary where it is staged, so a copy of the agent in one
        // account ships its own tree. It was refused while it meant "wherever the build ran".
        assertThat(read(MINIMAL + """
                install:
                  packaged:
                    - source: uc/tree.tar.gz
                      target: /opt/uc
                """).packaged()).containsExactly(new PackagedTree("uc/tree.tar.gz", "/opt/uc"));
    }

    @Test
    void refusesARelativePackagedSourceThatLeavesTheAgentsDirectory() {
        assertThatThrownBy(() -> read(MINIMAL + """
                install:
                  packaged:
                    - source: ../elsewhere/uc
                      target: /opt/uc
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("stays beside the agent's binary");
    }

    @Test
    void refusesAPackagedTargetThatIsNotAbsolute() {
        assertThatThrownBy(() -> read(MINIMAL + """
                install:
                  packaged:
                    - source: /usr/share/uc
                      target: opt/uc
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("must be an absolute path");
    }

    @Test
    void defaultsToASocketEndpoint() {

        // The safer one, and what every agent gets unless it says it cannot use a socket.
        final AgentDefinition definition = read(MINIMAL + """
                provider:
                  dialect: openai
                  socket_env: UC_SOCKET
                """);

        assertThat(definition.provider().endpoint()).isEqualTo(ProviderRoute.Endpoint.SOCKET);
    }

    @Test
    void readsAnAgentThatCanOnlyBeGivenAUrl() {

        final AgentDefinition definition = read(MINIMAL + """
                provider:
                  dialect: native
                  endpoint: url
                """);

        assertThat(definition.provider().endpoint()).isEqualTo(ProviderRoute.Endpoint.URL);
    }

    @Test
    void refusesAnEndpointItCannotServe() {

        // Named rather than ignored: an unknown endpoint would otherwise fall back to a socket
        // and the agent would be pointed at something it cannot address.
        assertThatThrownBy(() -> read(MINIMAL + """
                provider:
                  dialect: openai
                  endpoint: carrier-pigeon
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("must be 'socket' or 'url'");
    }

    @Test
    void readsWhereTheAgentKeepsItsOwnCredentials() {
        assertThat(read(MINIMAL + "config_dir: \"~/.example\"\n").configDirectory())
                .isEqualTo("~/.example");
    }

    @Test
    void leavesTheConfigDirectoryUnsetWhenNoneIsDeclared() {

        // An agent that manages no credential of its own must not get a guessed directory.
        assertThat(read(MINIMAL).configDirectory()).isNull();
    }

    @Test
    void readsWhatTheAgentShowsOnceAtWorkAndHowLongThatMayTake() {
        final AgentDefinition definition = read(MINIMAL + """
                session:
                  ready_marker: "at work"
                  ready_within_seconds: 45
                """);

        assertThat(definition.ready()).isEqualTo(new ReadyMarker("at work", 45));
        // And through the description the kit reads, unchanged.
        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(definition)).ready())
                .isEqualTo(new ReadyMarker("at work", 45));
    }

    @Test
    void anAgentWithNoStableReadyTextDeclaresNone() {
        assertThat(read(MINIMAL).ready()).isNull();
    }

    @Test
    void refusesABoundWithNothingToBound() {
        assertThatThrownBy(() -> read(MINIMAL + "session:\n  ready_within_seconds: 30\n"))
                .isInstanceOf(AgentException.class).hasMessageContaining("none is declared");
    }

    @Test
    void refusesAMarkerOfMoreThanOneLineAndABoundPastTheLimit() {
        assertThatThrownBy(() -> new ReadyMarker("one\ntwo", null)).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new ReadyMarker("ok", ReadyMarker.MOST_SECONDS + 1)).isInstanceOf(AgentException.class);
    }

    @Test
    void aModeWrittenAsANumberIsRefusedBecauseYamlReadsItInOctal() {

        // 'mode: 0644' is the number 420 to YAML, and the file was installed as 0420 - nobody may read it but its
        // owner's group; '0755' became 493 and was refused with words that did not say why.
        assertThatThrownBy(() -> read(MINIMAL + """
                install:
                  version: "1.0.0"
                  artifacts:
                    - url: https://example.com/uc
                      sha256: "%s"
                      target: /usr/local/bin/uc
                      mode: 0644
                """.formatted("a".repeat(64)))).isInstanceOf(AgentException.class).hasMessageContaining("'0644'");
        assertThat(read(MINIMAL + """
                install:
                  version: "1.0.0"
                  artifacts:
                    - url: https://example.com/uc
                      sha256: "%s"
                      target: /usr/local/bin/uc
                      mode: "0644"
                """.formatted("a".repeat(64))).artifacts().getFirst().mode()).isEqualTo("0644");
    }

    @Test
    void whatGoesIntoTheImageBuildIsAPathOrPlainWordsNothingThatStartsAnotherCommand() {

        // The target went into the build script unquoted, so ';' ran a second command; a line break in a reason or a
        // packaged target started an instruction of its own.
        assertThatThrownBy(() -> new InstallArtifact("https://example.com/uc", "a".repeat(64),
                "/usr/local/bin/uc;curl x|sh", "0755", false, null, null)).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new InstallArtifact("https://example.com/uc", null, "/usr/local/bin/uc", "0755", true,
                "no digest\nRUN curl x|sh", null)).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new PackagedTree("uc/tree", "/opt/uc\nRUN id")).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new PackagedTree("uc/tree", "/opt/my tree")).isInstanceOf(AgentException.class);
        assertThat(new PackagedTree("uc/tree", "/opt/uc-1.2_x").target()).isEqualTo("/opt/uc-1.2_x");
    }
}
