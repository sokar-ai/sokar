package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the agent protocol by running both halves against each other over a real socket.
 * <p>
 * Not by calling the server's methods directly: the wire is where the two sides can disagree, and
 * a test that skips it would pass while a released Sokar and a released agent failed to talk.
 */
class AgentProtocolTest {

    private static final String DEFINITION = """
            name: example
            label: Example
            binary: example-cli
            git_identity:
              name: Example
              email: noreply@example.com
            headless:
              prompt_flag: "-p"
              model_flag: "--model"
            session:
              supports_resume: true
              resume_flag: "--resume"
            provider:
              token_env:
                oauth: EXAMPLE_OAUTH
                _default: EXAMPLE_KEY
            allowed_domains:
              - api.example.com
            """;

    private static Agent agent() {
        final AgentDefinition definition =
                AgentDefinitionReader.read(new StringReader(DEFINITION), "test.yaml");
        return new Agent() {
            @Override
            public AgentDefinition definition() {
                return definition;
            }

            @Override
            public CredentialExtractor credentialExtractor() {
                return new EnvFileExtractor(".env", "EXAMPLE_KEY", "api-key");
            }

            @Override
            public LogFormatter logFormatter() {
                return line -> line.startsWith("noise") ? null : line.toUpperCase();
            }
        };
    }

    @Test
    void aDefinitionSurvivesTheWire(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            final AgentDefinition sent = agent().definition();
            final AgentDefinition received = AgentDefinitionJson.read(
                    AgentDefinitionJson.write(sent));

            // Every field, not just the ones a caller happens to read first: a field lost in
            // translation is behavior nobody declared.
            assertThat(received).isEqualTo(sent);
        }
    }

    @Test
    void anArtifactsDigestAndLicenseSurviveTheWire() {
        final AgentDefinition pinned = AgentDefinitionReader.read(new StringReader("""
                name: example
                binary: example-cli
                git_identity:
                  name: Example
                  email: noreply@example.com
                headless:
                  prompt_flag: "-p"
                install:
                  version: "2.1.267"
                  artifacts:
                    - url: https://downloads.example/cli/2.1.267/cli
                      sha256: "%s"
                      license: "SEE LICENSE IN README.md"
                      target: /usr/local/bin/example-cli
                """.formatted("a".repeat(64))), "t.yaml");

        final AgentDefinition round = AgentDefinitionJson.read(AgentDefinitionJson.write(pinned));

        assertThat(round.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.license()).isEqualTo("SEE LICENSE IN README.md");
            assertThat(artifact.sha256()).isEqualTo("a".repeat(64));
        });
        assertThat(round).isEqualTo(pinned);
    }

    @Test
    void anArtifactWithoutALicenseSurvivesWithoutOne() {
        final AgentDefinition pinned = AgentDefinitionReader.read(new StringReader("""
                name: example
                binary: example-cli
                git_identity:
                  name: Example
                  email: noreply@example.com
                headless:
                  prompt_flag: "-p"
                install:
                  version: "1.0.0"
                  artifacts:
                    - url: https://downloads.example/cli
                      sha256: "%s"
                      target: /usr/local/bin/example-cli
                """.formatted("b".repeat(64))), "t.yaml");

        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(pinned)).artifacts())
                .singleElement().extracting(InstallArtifact::license).isNull();
    }

    @Test
    void nullFieldsSurviveAsNull() {

        final AgentDefinition minimal = AgentDefinitionReader.read(new StringReader("""
                name: example
                binary: example-cli
                git_identity:
                  name: Example
                  email: noreply@example.com
                headless:
                  prompt_flag: "-p"
                """), "t.yaml");

        final AgentDefinition round = AgentDefinitionJson.read(AgentDefinitionJson.write(minimal));

        assertThat(round.headless().modelFlag()).isNull();
        assertThat(round.resumeFlag()).isNull();
        assertThat(round.provider()).isNull();
        assertThat(round).isEqualTo(minimal);
    }

    @Test
    void describeCarriesTheProtocolVersion(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.sokar.wire.varlink.VarlinkClient(server.socketPath())) {
                final Map<String, Object> answer = client.call(AgentProtocol.DESCRIBE, Map.of());

                assertThat(((Number) answer.get("protocolVersion")).intValue())
                        .isEqualTo(AgentProtocol.VERSION);
            }
        }
    }

    @Test
    void buildsACommandAcrossTheWire(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.sokar.wire.varlink.VarlinkClient(server.socketPath())) {
                final Object command = client.call(AgentProtocol.BUILD_COMMAND,
                        Map.of("prompt", "go", "model", "big")).get("command");

                assertThat((List<?>) command).map(String::valueOf)
                        .containsExactly("example-cli", "--model", "big", "-p", "go");
            }
        }
    }

    @Test
    void theClientSendsEveryFieldOfTheSetupContext() {

        // The failure this guards against is silent on both sides: a field added to the record,
        // read by the agent, never sent by the client. Comparing against the record's own
        // components means a new field breaks this test rather than a task.
        final SetupContext context = new SetupContext("sokar_pt_x", "api-key", "/workspace",
                "http://127.0.0.1:9419/api/v1", "openrouter");

        assertThat(context.parameters().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(SetupContext.class.getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertThat(context.parameters().values()).doesNotContainNull();
    }

    @Test
    void everyPartOfTheSetupContextSurvivesTheWire(@TempDir Path dir) throws Exception {

        // A regression test with a cause: the provider name was added to the context, the agent
        // read it, and the client never sent it - so a task got registerProvider("") and failed
        // to authenticate. Nothing below the wire could see that, because both sides compiled.
        final Agent echoing = new Agent() {
            @Override
            public AgentDefinition definition() {
                return AgentDefinitionReader.read(new StringReader(DEFINITION), "test.yaml");
            }

            @Override
            public ContainerSetup containerSetup() {
                return context -> List.of(new ContainerFile("/echo",
                        String.join("|", context.token(), context.credentialType(),
                                context.workspace(), context.endpoint(), context.provider()),
                        false));
            }
        };

        try (AgentServer server = new AgentServer(echoing, dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.sokar.wire.varlink.VarlinkClient(
                    server.socketPath())) {
                final Object files = client.call(AgentProtocol.CONTAINER_SETUP,
                        Map.of("token", "sokar_pt_x", "credentialType", "api-key",
                                "workspace", "/workspace", "endpoint", "http://127.0.0.1:9419/api/v1",
                                "provider", "openrouter")).get("files");

                assertThat((List<?>) files).singleElement()
                        .extracting(f -> ((Map<?, ?>) f).get("content"))
                        .isEqualTo("sokar_pt_x|api-key|/workspace|"
                                + "http://127.0.0.1:9419/api/v1|openrouter");
            }
        }
    }

    @Test
    void extractsACredentialAcrossTheWire(@TempDir Path dir) throws Exception {

        java.nio.file.Files.writeString(dir.resolve(".env"), "EXAMPLE_KEY=\"sk-example\"\n");

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.sokar.wire.varlink.VarlinkClient(server.socketPath())) {
                final Map<String, Object> answer = client.call(AgentProtocol.EXTRACT_CREDENTIAL,
                        Map.of("configDirectory", dir.toString()));

                assertThat(answer.get("found")).isEqualTo(Boolean.TRUE);
                assertThat(answer.get("secret")).isEqualTo("sk-example");
                assertThat(answer.get("type")).isEqualTo("api-key");
            }
        }
    }

    @Test
    void reportsNotLoggedInWithoutInventingACredential(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.sokar.wire.varlink.VarlinkClient(server.socketPath())) {
                final Map<String, Object> answer = client.call(AgentProtocol.EXTRACT_CREDENTIAL,
                        Map.of("configDirectory", dir.resolve("nowhere").toString()));

                assertThat(answer.get("found")).isEqualTo(Boolean.FALSE);
                assertThat(answer).doesNotContainKey("secret");
            }
        }
    }

    @Test
    void streamsFormattedLogLinesAndDropsTheOnesTheAgentSuppresses(@TempDir Path dir)
            throws Exception {

        java.nio.file.Files.writeString(dir.resolve("raw.log"), "one\nnoise here\ntwo\nthree\n");

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            final List<String> lines = new java.util.ArrayList<>();
            try (var client = new org.fuin.sokar.wire.varlink.VarlinkClient(server.socketPath())) {
                client.callMore(AgentProtocol.FORMAT_LOG,
                        Map.of("source", dir.resolve("raw.log").toString()), reply -> {
                            lines.add(String.valueOf(reply.get("line")));
                            return true;
                        });
            }
            assertThat(lines).containsExactly("ONE", "TWO", "THREE");
        }
    }

    @Test
    void refusesACallItCannotHonour(@TempDir Path dir) throws Exception {

        try (AgentServer server = new AgentServer(agent(), dir.resolve("a.sock"))) {
            Thread.ofVirtual().start(server::serve);

            try (var client = new org.fuin.sokar.wire.varlink.VarlinkClient(server.socketPath())) {
                assertThatThrownBy(() -> client.call(AgentProtocol.BUILD_COMMAND, Map.of()))
                        .hasMessageContaining("needs a prompt");
            }
        }
    }
}
