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

    /** The script the image build writes, as the definition renders it. */
    private String script() {
        return String.join("\n", agent.definition().installAsRoot());
    }

    /** Every name the script actually asks the resolver for. */
    private List<String> lookups() {
        return script().lines()
                .map(String::strip)
                .map(line -> line.replaceFirst("^if ", ""))
                .filter(line -> line.startsWith("getent hosts "))
                .map(line -> line.split(" ")[2])
                .toList();
    }

    @Test
    void reportsTheVersionItsDefinitionPins() {
        final String version = agent.definition().version();

        assertThat(version).isNotNull().isNotEqualTo("${agent.cli.version}");
        assertThat(script()).contains("echo '" + version + " (Sokar stub agent)'");
    }

    @Test
    void looksUpEveryDomainItDeclares() {

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
    void redeemsItsTokenThroughTheSocketTheProviderSectionNames() {
        final String socketVariable = agent.definition().provider().socketEnvironment();

        assertThat(socketVariable).isNotNull();
        assertThat(script()).contains("--unix-socket \"$" + socketVariable + "\"");
    }

    @Test
    void writesTheBinaryItsDefinitionSaysItIsInvokedAs() {
        final String binary = agent.definition().binary();

        assertThat(script())
                .contains("> /usr/local/bin/" + binary + " <<'STUB'")
                .contains("chmod 0755 /usr/local/bin/" + binary);
    }

    @Test
    void leavesNothingForTheImageBuildToFetch() {
        assertThat(agent.definition().artifacts()).isEmpty();
        assertThat(agent.definition().packaged()).isEmpty();
        assertThat(agent.definition().installAsAgent()).isEmpty();
    }

    @Test
    void writesAShellScriptTheShellAccepts() throws Exception {
        final String all = script();
        final int start = all.indexOf("<<'STUB'\n") + "<<'STUB'\n".length();
        final String body = all.substring(start, all.indexOf("\nSTUB\n"));

        final java.nio.file.Path file = java.nio.file.Files.createTempFile("stub-cli", ".sh");
        try {
            java.nio.file.Files.writeString(file, body);
            final Process process = new ProcessBuilder("sh", "-n", file.toString())
                    .redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes());
            assertThat(process.waitFor()).withFailMessage("sh rejected the script: %s", output)
                    .isZero();
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
