package org.fuin.sokar.agent.api;

import org.jspecify.annotations.Nullable;

/**
 * One file an agent's image build fetches from the network.
 * <p>
 * Declared rather than left inside a shell fragment, so that two questions have answers without
 * reading a build log: <em>what exactly did this image install</em>, and <em>was it the thing the
 * publisher intended</em>. The reference implementation answers neither - it installs agent CLIs
 * with {@code curl -fsSL https://…/install.sh | bash}, re-resolved on every build.
 * <p>
 * <strong>A digest is required unless the artifact says otherwise out loud.</strong> Some vendors
 * publish none, and pretending otherwise would be worse than admitting it: {@code unverified} with
 * a stated reason keeps the gap visible instead of making a uniform-looking file that is only
 * sometimes telling the truth.
 *
 * @param url Where to fetch it.
 * @param sha256 Expected digest, or {@code null} when {@code unverified}.
 * @param target Absolute path to install it at.
 * @param mode Octal file mode, for example {@code 0755}.
 * @param unverified Whether this artifact is knowingly unverifiable.
 * @param reason Why it is unverifiable; required when {@code unverified}.
 * @param license The license the publisher declares for this release - an SPDX id or, where it gives
 *     none, the name it uses - or {@code null} when nobody has recorded one.
 */
public record InstallArtifact(String url, @Nullable String sha256, String target, String mode,
        boolean unverified, @Nullable String reason, @Nullable String license) {

    /**
     * Constructor with all data.
     *
     * @param url Where to fetch it.
     * @param sha256 Expected digest, or {@code null} when unverified.
     * @param target Absolute path to install it at.
     * @param mode Octal file mode.
     * @param unverified Whether it is knowingly unverifiable.
     * @param reason Why; required when unverified.
     * @param license The declared license, or {@code null}.
     */
    public InstallArtifact {
        if (license != null) {
            // Recorded in a bill and compared there: a newline or a quote would be a second value.
            requirePlain("Install artifact license", license);
        }
        if (!url.startsWith("https://")) {
            // Not a style preference: a plaintext fetch lets anyone on the path replace the
            // artifact, and the digest below is only worth having if the URL is worth trusting.
            throw new AgentException("Install artifact URL must be https: " + url);
        }
        if (!target.startsWith("/")) {
            throw new AgentException("Install target must be absolute: " + target);
        }
        // Both of these are interpolated into the generated build script, so a quote or a newline
        // in either is not a bad value - it is another line of the script, contributed by whatever
        // answered the agent protocol. The build already runs an agent's own install fragments, so
        // this is not a new capability for a trusted agent; it is refusing malformed data at the
        // boundary instead of discovering it as a build that does something else.
        requirePlain("Install target", target);
        requirePlain("Install artifact URL", url);
        if (!mode.matches("0?[0-7]{3}")) {
            throw new AgentException("Install mode must be octal, for example 0755: " + mode);
        }
        if (unverified) {
            if (reason == null || reason.isBlank()) {
                throw new AgentException("Artifact " + url
                        + " is marked unverified but gives no reason. State why the publisher"
                        + " offers no digest, so the gap is visible rather than habitual.");
            }
            if (sha256 != null) {
                throw new AgentException("Artifact " + url
                        + " has a digest and is also marked unverified. Pick one.");
            }
        } else {
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
                throw new AgentException("Artifact " + url
                        + " needs a lower-case 64-character sha256, or unverified: true with a"
                        + " reason");
            }
        }
    }

    /**
     * Constructor without a license, as every definition was written before one was recorded.
     *
     * @param url Where to fetch it.
     * @param sha256 Expected digest, or {@code null} when unverified.
     * @param target Absolute path to install it at.
     * @param mode Octal file mode.
     * @param unverified Whether it is knowingly unverifiable.
     * @param reason Why; required when unverified.
     */
    public InstallArtifact(String url, @Nullable String sha256, String target, String mode, boolean unverified,
            @Nullable String reason) {
        this(url, sha256, target, mode, unverified, reason, null);
    }

    /**
     * Returns the file name the artifact is installed as.
     *
     * @return Last path segment of the target.
     */
    public String fileName() {
        return target.substring(target.lastIndexOf('/') + 1);
    }

    /**
     * Refuses a value that would not survive being written into a script.
     *
     * @param what Which value it is, for the message.
     * @param value The value.
     */
    private static void requirePlain(String what, String value) {
        for (final char character : value.toCharArray()) {
            if (character < ' ' || character == '\'' || character == '"' || character == '\\'
                    || character == '$' || character == '`') {
                throw new AgentException(what + " may not contain quotes, backslashes, shell"
                        + " expansion characters or control characters: " + value);
            }
        }
    }
}
