package org.fuin.sokar.testing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;

/**
 * A {@link CommandRunner} that records what it was asked to run and answers from a script.
 * <p>
 * Lets the modules that drive {@code podman}, {@code nft} and {@code git} be tested for the
 * commands they build, which is where their behavior actually lives.
 */
public class FakeCommandRunner implements CommandRunner {

    private final Map<String, CommandResult> answers = new LinkedHashMap<>();

    private final List<Command> invocations = new ArrayList<>();

    private CommandResult fallback(Command command) {
        return new CommandResult(command, 0, "", "");
    }

    /**
     * Answers any command containing the given text with a successful result.
     *
     * @param match Text that must appear in the command line.
     * @param standardOutput Output to return.
     * @return This instance.
     */
    public FakeCommandRunner answering(String match, String standardOutput) {
        answers.put(match, new CommandResult(Command.of("placeholder"), 0, standardOutput, ""));
        return this;
    }

    /**
     * Answers any command containing the given text with a successful result that also says something on its error.
     *
     * @param match Text that must appear in the command line.
     * @param standardOutput Output to return.
     * @param standardError Error output to return.
     * @return This instance.
     */
    public FakeCommandRunner answering(String match, String standardOutput, String standardError) {
        answers.put(match, new CommandResult(Command.of("placeholder"), 0, standardOutput, standardError));
        return this;
    }

    /**
     * Answers any command containing the given text with a failure.
     *
     * @param match Text that must appear in the command line.
     * @param exitCode Exit code to return.
     * @param standardError Error output to return.
     * @return This instance.
     */
    public FakeCommandRunner failing(String match, int exitCode, String standardError) {
        answers.put(match, new CommandResult(Command.of("placeholder"), exitCode, "", standardError));
        return this;
    }

    @Override
    public CommandResult run(Command command) {
        invocations.add(command);
        final String line = command.describe();
        for (final Map.Entry<String, CommandResult> entry : answers.entrySet()) {
            if (line.contains(entry.getKey())) {
                final CommandResult answer = entry.getValue();
                return new CommandResult(command, answer.exitCode(),
                        answer.standardOutput(), answer.standardError());
            }
        }
        return fallback(command);
    }

    /**
     * Returns every command that was run, in order.
     *
     * @return Invocations.
     */
    public List<Command> invocations() {
        return List.copyOf(invocations);
    }

    /**
     * Returns every command that was run, as single lines.
     *
     * @return Readable invocations.
     */
    public List<String> lines() {
        return invocations.stream().map(Command::describe).toList();
    }

    /**
     * Returns the single command containing the given text.
     *
     * @param match Text that must appear in the command line.
     * @return The matching command.
     * @throws IllegalStateException If no command or more than one matches.
     */
    public Command only(String match) {
        final List<Command> found = invocations.stream()
                .filter(command -> command.describe().contains(match)).toList();
        if (found.size() != 1) {
            throw new IllegalStateException("Expected exactly one command containing '" + match
                    + "' but found " + found.size() + " in " + lines());
        }
        return found.getFirst();
    }
}
