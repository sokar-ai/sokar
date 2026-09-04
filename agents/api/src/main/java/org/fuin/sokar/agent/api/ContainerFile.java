package org.fuin.sokar.agent.api;

/**
 * One file an agent needs inside a task container before it starts.
 *
 * @param path Absolute path inside the container.
 * @param content What to write.
 * @param ownerOnly Whether the file must be readable only by the agent user.
 */
public record ContainerFile(String path, String content, boolean ownerOnly) {

    /**
     * Constructor.
     *
     * @param path Absolute path inside the container.
     * @param content What to write.
     * @param ownerOnly Whether to restrict it to the owner.
     */
    public ContainerFile {
        if (!path.startsWith("/")) {
            // Written by Sokar into a container it controls; a relative path would land wherever
            // the writing process happened to be.
            throw new AgentException("A container file needs an absolute path, not '" + path + "'");
        }
    }

    /**
     * Returns a file only the agent user may read, for anything holding a token.
     *
     * @param path Absolute path inside the container.
     * @param content What to write.
     * @return The file.
     */
    public static ContainerFile secret(String path, String content) {
        return new ContainerFile(path, content, true);
    }

    /**
     * Returns an ordinary file.
     *
     * @param path Absolute path inside the container.
     * @param content What to write.
     * @return The file.
     */
    public static ContainerFile of(String path, String content) {
        return new ContainerFile(path, content, false);
    }
}
