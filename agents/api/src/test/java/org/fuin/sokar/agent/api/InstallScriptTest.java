package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InstallArtifact} and {@link InstallScript}.
 * <p>
 * That the generated script actually refuses a tampered file was verified against a real container
 * and a real HTTP server: with the correct digest the tool installs and runs; with a wrong one
 * {@code sha256sum} reports {@code FAILED}, the {@code install} line never executes, and the layer
 * exits 1. What is pinned here is the script that produces that behavior.
 */
class InstallScriptTest {

    private static final String DIGEST =
            "6c8818fa22187aa555c242be4abbacc44d6b71a32ac9631ee7b2b5d12f51f752";

    private static InstallArtifact pinned() {
        return new InstallArtifact("https://example.com/tool", DIGEST,
                "/usr/local/bin/tool", "0755", false, null);
    }

    @Test
    void verifiesBeforeInstalling() {

        // Checking afterwards is the same as not checking: by then it has been executed.
        final List<String> lines = InstallScript.render(List.of(pinned()));
        final int check = indexOfLineContaining(lines, "sha256sum -c -");
        final int install = indexOfLineContaining(lines, "install -D");

        assertThat(check).isGreaterThan(-1);
        assertThat(install).isGreaterThan(check);
    }

    @Test
    void failsTheWholeLayerRatherThanContinuing() {

        // One RUN with set -e: the download, the check and the install either all happen or the
        // layer fails. Split across several RUNs, a failed check would leave a layer behind.
        final List<String> lines = InstallScript.render(List.of(pinned()));

        assertThat(lines.getFirst()).isEqualTo("RUN set -eux; \\");
        assertThat(lines.stream().filter(line -> line.startsWith("RUN ")).count()).isEqualTo(1);
    }

    @Test
    void refusesToDowngradeTheTransport() {

        // --proto '=https' stops a redirect walking the download down to plaintext, where the
        // digest below would be checking whatever an attacker chose to serve.
        assertThat(String.join("\n", InstallScript.render(List.of(pinned()))))
                .contains("--proto '=https'")
                .contains("--tlsv1.2");
    }

    @Test
    void marksAnUnverifiableArtifactInTheBuildItself() {

        final InstallArtifact unverifiable = new InstallArtifact("https://example.com/x",
                null, "/usr/local/bin/x", "0755", true, "the publisher offers no digest");

        final String rendered = String.join("\n", InstallScript.render(List.of(unverifiable)));

        assertThat(rendered).contains("# UNVERIFIED: the publisher offers no digest");
        assertThat(rendered).doesNotContain("sha256sum");
    }

    @Test
    void refusesAPlaintextUrl() {

        // A digest only means something if the URL is worth trusting.
        assertThatThrownBy(() -> new InstallArtifact("http://example.com/tool", DIGEST,
                "/usr/local/bin/tool", "0755", false, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("must be https");
    }

    @Test
    void refusesAMissingOrMalformedDigest() {

        assertThatThrownBy(() -> new InstallArtifact("https://example.com/tool", null,
                "/usr/local/bin/tool", "0755", false, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("needs a lower-case 64-character sha256");

        assertThatThrownBy(() -> new InstallArtifact("https://example.com/tool", "abc123",
                "/usr/local/bin/tool", "0755", false, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("sha256");

        // Upper case would pass a naive length check and then fail sha256sum inside the build,
        // which is a much worse place to find out.
        assertThatThrownBy(() -> new InstallArtifact("https://example.com/tool",
                DIGEST.toUpperCase(), "/usr/local/bin/tool", "0755", false, null))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void refusesToWaveSomethingThroughWithoutSayingWhy() {

        // 'unverified: true' with no reason is how a gap becomes a habit.
        assertThatThrownBy(() -> new InstallArtifact("https://example.com/x", null,
                "/usr/local/bin/x", "0755", true, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("gap is visible rather than habitual");
    }

    @Test
    void refusesToBeBothPinnedAndUnverified() {

        assertThatThrownBy(() -> new InstallArtifact("https://example.com/x", DIGEST,
                "/usr/local/bin/x", "0755", true, "because"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Pick one");
    }

    @Test
    void refusesARelativeTargetOrABadMode() {

        assertThatThrownBy(() -> new InstallArtifact("https://example.com/x", DIGEST,
                "bin/x", "0755", false, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("must be absolute");

        assertThatThrownBy(() -> new InstallArtifact("https://example.com/x", DIGEST,
                "/usr/local/bin/x", "rwxr-xr-x", false, null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("must be octal");
    }

    @Test
    void reportsWhichArtifactsCannotBeVerified() {

        final List<InstallArtifact> artifacts = List.of(pinned(),
                new InstallArtifact("https://example.com/y", null, "/usr/local/bin/y", "0755",
                        true, "no digest published"));

        assertThat(InstallScript.unverified(artifacts)).hasSize(1);
        assertThat(InstallScript.unverified(artifacts).getFirst().url())
                .isEqualTo("https://example.com/y");
    }

    private static int indexOfLineContaining(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }
}
