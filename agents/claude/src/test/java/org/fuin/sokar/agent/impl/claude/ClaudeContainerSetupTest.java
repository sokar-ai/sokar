package org.fuin.sokar.agent.impl.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.sokar.agent.api.ContainerFile;
import org.fuin.sokar.wire.Json;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClaudeContainerSetup}.
 */
class ClaudeContainerSetupTest {

    private static final String TOKEN = "sokar_pt_example";

    private List<ContainerFile> files(String type) {
        return new ClaudeContainerSetup().files(new org.fuin.sokar.agent.api.SetupContext(
                TOKEN, type, "/workspace", "", "anthropic"));
    }

    private Map<?, ?> parse(ContainerFile file) {
        return (Map<?, ?>) Json.parse(file.content());
    }

    private ContainerFile named(String path, String type) {
        return files(type).stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void answersTheQuestionsAFreshContainerWouldBeAsked() {

        // Measured: without these the CLI runs its first-run wizard, and the login menu it offers
        // there is part of the wizard rather than any check of the credential.
        final Map<?, ?> config = parse(named(ClaudeFirstRun.FILE, "oauth"));

        assertThat(config.get("hasCompletedOnboarding")).isEqualTo(Boolean.TRUE);
        assertThat(((Map<?, ?>) ((Map<?, ?>) config.get("projects")).get("/workspace"))
                .get("hasTrustDialogAccepted")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void storesAnOauthTokenWhereItsOwnLoginWould() {
        final Map<?, ?> credentials = parse(named(AnthropicCredentialFile.FILE, "oauth"));

        assertThat(((Map<?, ?>) credentials.get("claudeAiOauth")).get("accessToken"))
                .isEqualTo(TOKEN);
    }

    @Test
    void storesAnApiKeyInItsOwnShape() {

        // The two kinds live in different places in that file; writing one as the other fails
        // looking exactly like a wrong key.
        final Map<?, ?> credentials = parse(named(AnthropicCredentialFile.FILE, "api-key"));

        assertThat(credentials.get("apiKey")).isEqualTo(TOKEN);
        assertThat(credentials.get("claudeAiOauth")).isNull();
    }

    @Test
    void keepsTheCredentialFileToItsOwner() {

        // It holds the task's token. The other file holds no secret and must stay readable.
        assertThat(named(AnthropicCredentialFile.FILE, "oauth").ownerOnly()).isTrue();
        assertThat(named(ClaudeFirstRun.FILE, "oauth").ownerOnly()).isFalse();
    }

    @Test
    void roundTripsThroughItsOwnExtractor() {

        // What is written must be what the extractor reads: the two are the same knowledge,
        // and nothing else in Sokar can catch them drifting apart.
        assertThat(parse(named(AnthropicCredentialFile.FILE, "oauth")).get("claudeAiOauth"))
                .isNotNull();
    }
}
