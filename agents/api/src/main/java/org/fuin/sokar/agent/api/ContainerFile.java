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
        // Checked here because this is where an agent's answer becomes Sokar's instruction. The
        // path arrives over the agent protocol - it is the agent's word for where its own
        // configuration goes - and everything downstream treats it as already true.
        //
        // What is refused and why:
        //   quotes, backslashes, and anything below space  - these once reached a shell command
        //       built by concatenation, where a single quote ends the quoting and the rest of the
        //       path becomes another command. The shell is gone now; the refusal stays, because a
        //       path containing one was never a path anybody meant.
        //   '..' anywhere                                  - a file placed relative to a
        //       directory it is not under is a file placed somewhere nobody asked for.
        //   a trailing '/' or an empty segment             - names a directory, or nothing.
        for (final char character : path.toCharArray()) {
            if (character < ' ' || character == '\'' || character == '"' || character == '\\') {
                throw new AgentException("A container file path may not contain quotes, backslashes"
                        + " or control characters: '" + path + "'");
            }
        }
        if (path.contains("/../") || path.endsWith("/..") || path.contains("//")
                || path.endsWith("/")) {
            throw new AgentException("A container file needs a plain absolute path without '..'"
                    + " or empty segments, not '" + path + "'");
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
