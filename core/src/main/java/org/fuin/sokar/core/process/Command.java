package org.fuin.sokar.core.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An external program to run.
 *
 * @param arguments Program and its arguments, never empty.
 * @param workingDirectory Directory to run in, or {@code null} for the current one.
 * @param environment Variables to add to the inherited environment.
 * @param input Text written to standard input, or {@code null} to write nothing.
 */
public record Command(List<String> arguments, @Nullable Path workingDirectory,
        Map<String, String> environment, @Nullable String input, boolean inheritsEnvironment) {

    /**
     * Constructor with all data.
     *
     * @param arguments Program and its arguments, never empty.
     * @param workingDirectory Directory to run in, or {@code null} for the current one.
     * @param environment Variables to add to the inherited environment - or, when it is not inherited, the
     *        whole environment.
     * @param input Text written to standard input, or {@code null} to write nothing.
     * @param inheritsEnvironment Whether the caller's environment is inherited at all.
     */
    public Command {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("A command needs at least a program name");
        }
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
    }

    /**
     * Constructor for a command that inherits the caller's environment and adds to it.
     *
     * @param arguments Program and its arguments, never empty.
     * @param workingDirectory Directory to run in, or {@code null} for the current one.
     * @param environment Variables to add to the inherited environment.
     * @param input Text written to standard input, or {@code null} to write nothing.
     */
    public Command(List<String> arguments, @Nullable Path workingDirectory, Map<String, String> environment,
            @Nullable String input) {
        this(arguments, workingDirectory, environment, input, true);
    }

    /**
     * Returns a copy of this command that starts from a chosen environment instead of the caller's: the base
     * given, and this command's own variables on top. Nothing else of the caller's reaches it.
     *
     * @param base What it starts from.
     * @return New command.
     */
    public Command withChosenEnvironment(Map<String, String> base) {
        final Map<String, String> chosen = new java.util.LinkedHashMap<>(base);
        chosen.putAll(environment);
        return new Command(arguments, workingDirectory, chosen, input, false);
    }

    /**
     * Creates a command with no working directory, environment or input.
     *
     * @param arguments Program and its arguments.
     * @return New command.
     */
    public static Command of(String... arguments) {
        return new Command(List.of(arguments), null, Map.of(), null);
    }

    /**
     * Creates a command with no working directory, environment or input.
     *
     * @param arguments Program and its arguments.
     * @return New command.
     */
    public static Command of(List<String> arguments) {
        return new Command(arguments, null, Map.of(), null);
    }

    /**
     * Returns a copy of this command that writes the given text to standard input.
     *
     * @param text Text to write.
     * @return New command.
     */
    public Command withInput(String text) {
        return new Command(arguments, workingDirectory, environment, text, inheritsEnvironment);
    }

    /**
     * Returns a copy of this command with variables added to the environment it inherits.
     * <p>
     * This is where a value goes when it must not be an argument: a child's environment is
     * readable only by its owner, and its argument list is readable by everyone on the machine.
     *
     * @param variables Variables to add.
     * @return New command.
     */
    public Command withEnvironment(Map<String, String> variables) {
        final Map<String, String> merged = new java.util.LinkedHashMap<>(environment);
        merged.putAll(variables);
        return new Command(arguments, workingDirectory, merged, input, inheritsEnvironment);
    }

    /**
     * Returns a copy of this command running in the given directory.
     *
     * @param directory Working directory.
     * @return New command.
     */
    public Command in(Path directory) {
        return new Command(arguments, directory, environment, input, inheritsEnvironment);
    }

    /**
     * Returns the program name.
     *
     * @return First argument.
     */
    public String program() {
        return arguments.getFirst();
    }

    /**
     * Returns the command as a single line, for log and error messages. Not shell-quoted, and not
     * meant to be executed.
     *
     * @return Readable form.
     */
    public String describe() {
        return String.join(" ", arguments);
    }
}
