package org.fuin.sokar.core.process;

import org.jspecify.annotations.Nullable;

/**
 * An external program could not be started, or ended with a non-zero exit code.
 */
public class CommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Command command;

    @Nullable
    private final transient CommandResult result;

    /**
     * Constructor for a program that ran and failed.
     *
     * @param command The command.
     * @param result What it left behind.
     */
    public CommandException(Command command, CommandResult result) {
        super(message(command, result));
        this.command = command;
        this.result = result;
    }

    /**
     * Constructor for a program that could not be started.
     *
     * @param command The command.
     * @param cause Cause.
     */
    public CommandException(Command command, Throwable cause) {
        super("Cannot run '" + command.describe() + "'"
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()), cause);
        this.command = command;
        this.result = null;
    }

    private static String message(Command command, CommandResult result) {
        // The failing command's own error output is the useful part. Without it the caller sees
        // 'exit code 125' and has to reproduce the run by hand to learn anything.
        final String detail = result.standardError().isBlank()
                ? result.standardOutput().strip()
                : result.standardError().strip();
        return "'" + command.describe() + "' failed with exit code " + result.exitCode()
                + (detail.isEmpty() ? "" : ": " + detail);
    }

    /**
     * Returns the command that failed.
     *
     * @return Command.
     */
    public Command getCommand() {
        return command;
    }

    /**
     * Returns what the program left behind.
     *
     * @return Result, or {@code null} if the program never started.
     */
    @Nullable
    public CommandResult getResult() {
        return result;
    }
}
