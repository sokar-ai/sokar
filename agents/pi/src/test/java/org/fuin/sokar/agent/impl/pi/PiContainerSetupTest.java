package org.fuin.sokar.agent.impl.pi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.sokar.agent.api.ContainerFile;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PiContainerSetup}.
 */
class PiContainerSetupTest {

    private static final String TOKEN = "sokar_pt_example";

    private List<ContainerFile> files(String endpoint) {
        return new PiContainerSetup().files(TOKEN, "api-key", "/workspace", endpoint);
    }

    @Test
    void writesAnExtensionPiDiscoversOnItsOwn() {

        // Auto-discovered from ~/.pi/agent/extensions, which is why this is a file rather than
        // an install command.
        assertThat(files("http://127.0.0.1:9419")).singleElement()
                .extracting(ContainerFile::path)
                .isEqualTo("/home/agent/.pi/agent/extensions/sokar-route.ts");
    }

    @Test
    void pointsPiAtTheBrokerUnderTheDialectPath() {

        // OpenRouter serves the OpenAI dialect under /api/v1; a base without it answers 404.
        assertThat(files("http://127.0.0.1:9419").getFirst().content())
                .contains("\"http://127.0.0.1:9419/api/v1\"")
                .contains("registerProvider(\"openrouter\"");
    }

    @Test
    void carriesTheTaskTokenRatherThanACredential() {

        assertThat(files("http://127.0.0.1:9419").getFirst().content()).contains(TOKEN);
        assertThat(files("http://127.0.0.1:9419").getFirst().ownerOnly())
                .as("it holds a token, so it is not world readable").isTrue();
    }

    @Test
    void writesNothingWhenNothingWasBrokered() {

        // An agent pointed at nothing would otherwise get an extension naming an endpoint that
        // does not exist, which fails later and further away.
        assertThat(files("")).isEmpty();
    }

    @Test
    void survivesATokenWithCharactersThatWouldBreakTheFile() {

        final List<ContainerFile> files = new PiContainerSetup()
                .files("tok\"en\\with\nquotes", "api-key", "/workspace", "http://127.0.0.1:9419");

        assertThat(files.getFirst().content())
                .as("written as a JSON literal, so a stray quote cannot end the string")
                .contains("\"tok\\\"en\\\\with\\nquotes\"");
    }
}
