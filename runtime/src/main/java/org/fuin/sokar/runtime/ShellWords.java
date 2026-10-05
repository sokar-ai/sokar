package org.fuin.sokar.runtime;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns an argument list into one string a shell will split back into the same arguments.
 * <p>
 * Needed because the interactive attach runs its first command through {@code bash -lc}, which
 * takes a script rather than an argument vector. Joining with spaces would be right until the
 * first argument containing one, and then wrong in a way that shows up as an agent started with
 * arguments nobody wrote.
 */
public final class ShellWords {

    private ShellWords() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Quotes each argument and joins them with a space.
     * <p>
     * Single quotes, because inside them a shell interprets nothing at all - no expansion, no
     * escape, no substitution. The one character that cannot appear is a single quote itself,
     * which is why one is closed, escaped and reopened.
     *
     * @param arguments The arguments.
     * @return One shell command line.
     */
    public static String quote(List<String> arguments) {
        return arguments.stream()
                .map(argument -> "'" + argument.replace("'", "'\\''") + "'")
                .collect(Collectors.joining(" "));
    }
}
