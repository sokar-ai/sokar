package org.fuin.sokar.agent.api;

/**
 * A directory an agent ships inside its own package, copied into the image rather than downloaded.
 * <p>
 * The alternative to {@link InstallArtifact}, and chosen per agent because the two suit different
 * tools. A single self-contained binary is best fetched from a pinned URL and checked against a
 * digest: the package stays small and the layer cache does the rest. A tool that is a tree of
 * hundreds of files has no such URL, and verifying it at image-build time would mean trusting a
 * package manager's network fetch on every build.
 * <p>
 * Shipping it instead moves the verification to where the package is built, once, and leaves the
 * image build with nothing to fetch at all - which is a stronger guarantee than a pinned URL, not
 * a weaker one: the same package cannot install different bytes on different days.
 *
 * The source may be a directory or a {@code .tar.gz} of one. A tarball is usually the better
 * choice: it is one file for the packagers to carry and one file for an operator to check, and
 * unpacking it once beats copying tens of thousands of files for every task.
 *
 * @param source Path on the host, installed there by the agent's own package: absolute, or relative to
 *        the directory the agent's binary is in - so a copy of the agent in one account's directory
 *        ships its own tree.
 * @param target Absolute path inside the image.
 */
public record PackagedTree(String source, String target) {

    /**
     * Constructor with all data.
     *
     * @param source Path on the host, absolute or beside the agent's binary.
     * @param target Absolute path inside the image.
     */
    public PackagedTree {
        if (source == null || source.isBlank()) {
            throw new AgentException("A packaged source must name a path: " + source);
        }
        // Beside the binary means beside it: '..' would make a relative path mean anywhere.
        if (!source.startsWith("/") && java.util.List.of(source.split("/")).contains("..")) {
            throw new AgentException("A relative packaged source stays beside the agent's binary: " + source);
        }
        if (target == null || !target.startsWith("/")) {
            throw new AgentException("A packaged target must be an absolute path: " + target);
        }
    }

    /**
     * Returns the directory name this tree is staged under inside the build context.
     *
     * @return A name derived from the target, stable across builds so the layer cache holds.
     */
    public String stagingName() {
        return "sokar-packaged" + target.replace('/', '-');
    }

    /**
     * Tells whether the source is an archive to unpack rather than a directory to copy.
     *
     * @return {@code true} for a gzipped tarball.
     */
    public boolean archive() {
        return source.endsWith(".tar.gz") || source.endsWith(".tgz");
    }
}
