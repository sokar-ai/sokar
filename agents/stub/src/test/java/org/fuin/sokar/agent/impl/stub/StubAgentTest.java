package org.fuin.sokar.agent.impl.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StubAgent}.
 * <p>
 * The ones that matter check the definition and the script it installs agree. A stub whose
 * definition promises a reach the script never makes would pass every check in the acceptance
 * suite while proving nothing, which is the one way this agent can be worse than no agent.
 */
class StubAgentTest {

    private final StubAgent agent = new StubAgent();

    /** The container-build line, exactly as the image build receives it. */
    private String buildLine() {
        return String.join("\n", agent.definition().installAsRoot());
    }

    /**
     * The script that line actually writes, produced by running it.
     * <p>
     * Rendered rather than parsed: the tests below then check the file an image really gets,
     * and they keep working whatever form the line takes. An earlier version read the body out
     * of a here-document, which was both brittle and a form podman 4 cannot build at all.
     */
    private String renderedScript() throws Exception {
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("stub-cli", ".sh");
        try {
            final String command = buildLine()
                    .replaceFirst("^RUN ", "")
                    .replace(StubAgent.TARGET, file.toString());
            final Process process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes());
            assertThat(process.waitFor())
                    .withFailMessage("the build line did not run: %s", output).isZero();
            return java.nio.file.Files.readString(file);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /** Every name the script asks the resolver for. */
    private List<String> lookups() throws Exception {
        return renderedScript().lines()
                .map(String::strip)
                .filter(line -> line.startsWith("getent hosts "))
                .map(line -> line.split(" ")[2])
                .toList();
    }

    @Test
    void logsInByWritingAnOauthFixtureWhereItsDefinitionSaysAndItsExtractorFindsIt(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path home) throws Exception {

        // The chain a login takes, end to end on this side: the definition's login arguments run the script,
        // which writes where config_dir says, and the extractor every login goes through reads it as oauth.
        assertThat(agent.definition().loginArguments()).containsExactly("login");
        assertThat(agent.definition().configDirectory()).isEqualTo("~/.sokar-stub");
        final java.nio.file.Path script = home.resolve("sokar-stub-cli");
        java.nio.file.Files.writeString(script, renderedScript());
        final ProcessBuilder login = new ProcessBuilder("sh", script.toString(), "login").redirectErrorStream(true);
        login.environment().put("HOME", home.toString());
        login.environment().put("SOKAR_STUB_LINGER", "0");
        final Process process = login.start();
        final String said = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).isZero();
        assertThat(said).contains("stub: logged in");

        final java.nio.file.Path config = home.resolve(".sokar-stub");
        assertThat(java.nio.file.Files.getPosixFilePermissions(config.resolve(".credentials.json")))
                .containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        final var credential = agent.credentialExtractor().extract(config).orElseThrow();
        assertThat(credential.type()).isEqualTo("oauth");
        assertThat(String.valueOf(credential.secret())).isEqualTo("sokar-stub-fixture-token");
        // What renews it is handed over too, as a subscription login's is, under the agent API's names.
        assertThat(credential.attributes()).containsEntry("refreshToken", "sokar-stub-fixture-refresh")
                .containsEntry("tokenUrl", "https://example.com/sokar-stub/token").containsEntry("clientId", "sokar-stub")
                .hasEntrySatisfying("expiresAt", ends -> assertThat(Long.parseLong(ends))
                        .isGreaterThan(System.currentTimeMillis()));
    }

    @Test
    void reportsTheVersionItsDefinitionPins() throws Exception {
        final String version = agent.definition().version();

        assertThat(version).isNotNull().isNotEqualTo("${agent.cli.version}");
        assertThat(renderedScript()).contains(version + " (Sokar stub agent)");
    }

    @Test
    void looksUpEveryDomainItDeclares() throws Exception {

        // The lookup, not the name. An earlier version asserted the script merely mentioned each
        // domain and stayed green when the lookup was pointed at a third name, because the
        // domain was still in an echo beside it.
        final AgentDefinition definition = agent.definition();
        final List<String> declared = new ArrayList<>(definition.allowedDomains());
        declared.addAll(definition.refusedDomains());

        assertThat(definition.allowedDomains()).isNotEmpty();
        assertThat(definition.refusedDomains()).isNotEmpty();
        assertThat(lookups()).isNotEmpty().containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void declaresNoDomainAsBothGrantedAndRefused() {
        final AgentDefinition definition = agent.definition();

        assertThat(definition.allowedDomains())
                .doesNotContainAnyElementsOf(definition.refusedDomains());
    }

    @Test
    void redeemsItsTokenThroughTheSocketTheProviderSectionNames() throws Exception {
        final String socketVariable = agent.definition().provider().socketEnvironment();

        assertThat(socketVariable).isNotNull();
        assertThat(renderedScript()).contains("--unix-socket \"$" + socketVariable + "\"");
    }

    @Test
    void writesTheBinaryItsDefinitionSaysItIsInvokedAs() {
        assertThat(StubAgent.TARGET).endsWith("/" + agent.definition().binary());
        assertThat(buildLine())
                .contains("> " + StubAgent.TARGET)
                .contains("chmod 0755 " + StubAgent.TARGET);
    }

    @Test
    void usesNoConstructPodmanFourCannotParse() {

        // podman 4.9.3, which Ubuntu 24.04 ships, reads every line of a Containerfile as an
        // instruction. A here-document fails there with `Unknown instruction: "IF"` while
        // building fine on podman 5, which is how this reached an acceptance leg.
        assertThat(buildLine())
                .doesNotContain("<<")
                .startsWith("RUN ");
        for (final String line : agent.definition().installAsRoot()) {
            assertThat(line.strip().isEmpty()).isFalse();
        }
    }

    @Test
    void leavesNothingForTheImageBuildToFetch() {
        assertThat(agent.definition().artifacts()).isEmpty();
        assertThat(agent.definition().packaged()).isEmpty();
        assertThat(agent.definition().installAsAgent()).isEmpty();
    }

    @Test
    void writesAShellScriptTheShellAccepts() throws Exception {
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("stub-cli", ".sh");
        try {
            java.nio.file.Files.writeString(file, renderedScript());
            final Process process = new ProcessBuilder("sh", "-n", file.toString())
                    .redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes());
            assertThat(process.waitFor()).withFailMessage("sh rejected the script: %s", output)
                    .isZero();
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @org.junit.jupiter.api.Test
    void readsItsProvidersAnswerAsTheEndOfItsRun() {

        // The stub's last act is one call to its provider through the broker; what came back says how its run ended.
        final StubAgent stub = new StubAgent();

        org.assertj.core.api.Assertions.assertThat(stub.ended(
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"))
                .isEqualTo(new org.fuin.sokar.agent.api.AgentEnd(false, "invalid x-api-key", "provider", null));
        org.assertj.core.api.Assertions.assertThat(stub.ended("{\"type\":\"message\",\"content\":[]}"))
                .isEqualTo(new org.fuin.sokar.agent.api.AgentEnd(true, "", "agent", null));
        org.assertj.core.api.Assertions.assertThat(stub.ended("")).isNull();
        org.assertj.core.api.Assertions.assertThat(stub.ended("stub: example.com resolved")).isNull();
    }
}
