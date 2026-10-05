package org.fuin.sokar.core.process;

/**
 * Runs external programs.
 * <p>
 * An interface rather than a static call, because everything Sokar does to a container goes
 * through {@code podman}, {@code nft} and {@code git}. Tests that had to invoke those for real
 * would be slow, root-dependent and impossible to run in CI.
 */
@FunctionalInterface
public interface CommandRunner {

    /**
     * Runs a command to completion.
     *
     * @param command Command to run.
     * @return What it left behind, whatever the exit code.
     * @throws CommandException If the program could not be started.
     */
    CommandResult run(Command command);

    /**
     * Runs a command and fails if it does.
     *
     * @param command Command to run.
     * @return What it left behind.
     * @throws CommandException If the program could not be started, or exited non-zero.
     */
    default CommandResult runOrFail(Command command) {
        return run(command).orFail();
    }
}
