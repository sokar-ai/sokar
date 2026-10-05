package org.fuin.sokar.agent.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns declared artifacts into the container-build lines that fetch and verify them.
 * <p>
 * Generated rather than hand-written in each definition, so that the verification cannot be
 * forgotten in one agent and present in another. Every fetch is one {@code RUN} with
 * {@code set -eux}: the download, the digest check and the install either all happen or the layer
 * fails.
 * <p>
 * <strong>The digest is checked before the file is used, not after.</strong> Checking afterwards
 * is the same as not checking, because by then it has already been executed.
 */
public final class InstallScript {

    /** Temporary path artifacts are downloaded to before verification. */
    private static final String STAGING = "/tmp/sokar-download";

    private InstallScript() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Renders the build lines for a set of artifacts.
     *
     * @param artifacts What to fetch.
     * @return Container-build lines, one {@code RUN} per artifact.
     */
    public static List<String> render(List<InstallArtifact> artifacts) {

        final List<String> lines = new ArrayList<>();

        for (final InstallArtifact artifact : artifacts) {
            if (artifact.unverified()) {
                lines.add("# UNVERIFIED: " + artifact.reason());
                lines.add("# The publisher offers no digest, so this fetch is trusted on TLS alone.");
                lines.add("RUN set -eux; \\");
                lines.add("    curl -fsSL --proto '=https' --tlsv1.2 -o " + STAGING
                        + " '" + artifact.url() + "'; \\");
            } else {
                lines.add("RUN set -eux; \\");
                lines.add("    curl -fsSL --proto '=https' --tlsv1.2 -o " + STAGING
                        + " '" + artifact.url() + "'; \\");
                lines.add("    echo '" + artifact.sha256() + "  " + STAGING
                        + "' | sha256sum -c -; \\");
            }
            lines.add("    install -D -m " + artifact.mode() + " " + STAGING
                    + " " + artifact.target() + "; \\");
            lines.add("    rm -f " + STAGING);
            lines.add("");
        }

        if (!lines.isEmpty()) {
            lines.removeLast();
        }
        return List.copyOf(lines);
    }

    /**
     * Returns the artifacts that carry no digest.
     *
     * @param artifacts Artifacts to inspect.
     * @return The unverifiable ones.
     */
    public static List<InstallArtifact> unverified(List<InstallArtifact> artifacts) {
        return artifacts.stream().filter(InstallArtifact::unverified).toList();
    }
}
