package org.fuin.sokar.gate;

import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * The git repository a command was started in.
 */
public final class LocalRepository {

    private LocalRepository() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the root of the work tree holding a directory.
     *
     * @param runner Runs git.
     * @param directory Where to look.
     * @return The work tree root, or {@code null} when there is no repository.
     */
    @Nullable
    public static Path topLevel(CommandRunner runner, Path directory) {
        // git answers, so a worktree or submodule - where .git is a file - is recognized too.
        final CommandResult result = runner.run(Command.of(List.of(
                "git", "-C", directory.toString(), "rev-parse", "--show-toplevel")));
        if (!result.successful()) {
            return null;
        }
        final String path = result.standardOutput().strip();
        return path.isEmpty() ? null : Path.of(path);
    }
}
