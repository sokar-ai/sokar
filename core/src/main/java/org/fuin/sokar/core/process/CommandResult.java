package org.fuin.sokar.core.process;

/**
 * What an external program left behind.
 *
 * @param command The command that produced this result.
 * @param exitCode Exit code.
 * @param standardOutput Everything written to standard output.
 * @param standardError Everything written to standard error.
 */
public record CommandResult(Command command, int exitCode, String standardOutput, String standardError) {

    /**
     * Tells whether the program succeeded.
     *
     * @return {@code true} if the exit code is zero.
     */
    public boolean successful() {
        return exitCode == 0;
    }

    /**
     * Returns standard output without trailing line separators.
     *
     * @return Trimmed output.
     */
    public String trimmedOutput() {
        return standardOutput.strip();
    }

    /**
     * Returns this result, or throws if the program failed.
     *
     * @return This result.
     * @throws CommandException If the exit code is not zero.
     */
    public CommandResult orFail() {
        if (!successful()) {
            throw new CommandException(command, this);
        }
        return this;
    }
}
