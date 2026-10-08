package org.fuin.sokar.build.api;

/**
 * What a build reader implements: one forge, asked about one branch or one commit at a time.
 * <p>
 * A reader in its own repository implements this, and its main method hands it to {@link BuildMain#run}; Sokar finds
 * the executable, starts it, and asks it over {@link BuildProtocol#INTERFACE}.
 */
public interface BuildReader {

    /**
     * Returns the forge this reader reads, which is what a project file names under {@code builds.forge} and the name
     * its executable is installed under.
     *
     * @return Lower-case letters, digits and hyphens.
     */
    String forge();

    /**
     * Returns the commit a branch points at on the forge.
     *
     * @param target Where and with what.
     * @param branch The branch's name, such as {@code fix-login}; not a ref.
     * @return The full sha; "" when the forge has no such branch.
     * @throws BuildRefused When the forge would not answer.
     */
    String head(Target target, String branch);

    /**
     * Returns what the build of one commit did, across everything the forge ran for it.
     *
     * @param target Where and with what.
     * @param commit The full sha.
     * @param logs Which jobs' logs to hand over: each failed one, or every one once the verdict is final.
     * @return The build; {@link Build#UNKNOWN} when the forge holds none for the commit.
     * @throws BuildRefused When the forge would not answer.
     */
    Build look(Target target, String commit, Build.Logs logs);
}
