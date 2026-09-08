package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.UnmatchedArgumentException;

/**
 * What somebody sees when they type a command wrongly.
 * <p>
 * <strong>The default is written for whoever wrote the parser.</strong> Typing
 * {@code sokar unlock} answered "Unmatched argument at index 0: 'unlock'" - which describes the
 * parser's position in an array - and then suggested {@code sokar doctor}, because the built-in
 * suggestion compares spellings against the commands at that one level and {@code doctor} was
 * the nearest. Both halves are unhelpful at once: the wording is about parsing, and the advice
 * points somewhere unrelated.
 * <p>
 * <strong>What was actually wrong is usually knowable.</strong> {@code unlock} is not a
 * misspelling of anything; it is a real command one level down. Searching the whole tree for it
 * turns the answer into the thing somebody can act on: {@code sokar vault unlock}.
 */
final class CliErrors {

    private CliErrors() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the full command path of a subcommand with this name, wherever it is.
     * <p>
     * Breadth-first, so the shallowest match wins: a name that exists at two depths is more
     * likely meant as the shorter one.
     *
     * @param root The top-level command.
     * @param name What somebody typed.
     * @return The path a person would type, or empty when nothing has that name.
     */
    static Optional<String> pathTo(CommandLine root, String name) {
        final Deque<CommandLine> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            final CommandLine here = queue.removeFirst();
            for (final var entry : here.getSubcommands().entrySet()) {
                if (entry.getKey().equals(name)) {
                    return Optional.of(entry.getValue().getCommandSpec().qualifiedName(" "));
                }
                queue.addLast(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the top-level command, whichever one the parse failed in.
     * <p>
     * The search has to start there rather than where somebody was standing: the whole point is
     * to find a name that lives somewhere else.
     *
     * @param command Where the parse failed.
     * @return The root.
     */
    private static CommandLine rootOf(CommandLine command) {
        CommandLine here = command;
        while (here.getParent() != null) {
            here = here.getParent();
        }
        return here;
    }

    /**
     * Returns the closest command name to what was typed, when something is close enough.
     * <p>
     * Searched across the whole tree rather than one level, which is what the built-in
     * suggestion does: typing {@code sokar improt} should offer {@code sokar vault import}, and
     * comparing only against the top-level names cannot see it.
     * <p>
     * Two edits at most, and never for a very short word - at one or two characters almost
     * everything is within two edits of almost everything else, and a confident wrong suggestion
     * is worse than none.
     *
     * @param root The top-level command.
     * @param typed What somebody typed.
     * @return The nearest command's full path, or empty.
     */
    static Optional<String> nearestTo(CommandLine root, String typed) {
        if (typed.length() < 3) {
            return Optional.empty();
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        final Deque<CommandLine> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            final CommandLine here = queue.removeFirst();
            for (final var entry : here.getSubcommands().entrySet()) {
                final int distance = editDistance(typed, entry.getKey());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entry.getValue().getCommandSpec().qualifiedName(" ");
                }
                queue.addLast(entry.getValue());
            }
        }
        return bestDistance <= 2 ? Optional.ofNullable(best) : Optional.empty();
    }

    /**
     * Returns how many single-character edits turn one word into the other.
     *
     * @param one First word.
     * @param other Second word.
     * @return The distance.
     */
    static int editDistance(String one, String other) {
        final int[] previous = new int[other.length() + 1];
        final int[] current = new int[other.length() + 1];
        for (int j = 0; j <= other.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= one.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= other.length(); j++) {
                final int substitute = previous[j - 1]
                        + (one.charAt(i - 1) == other.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            System.arraycopy(current, 0, previous, 0, current.length);
        }
        return previous[other.length()];
    }

    /**
     * Returns the commands available where somebody was, for when nothing else can be said.
     *
     * @param command Where the parse failed.
     * @return The names, or empty.
     */
    private static List<String> available(CommandLine command) {
        return new ArrayList<>(command.getSubcommands().keySet());
    }

    /**
     * Returns the handler the CLI installs.
     *
     * @return A handler that says what was wrong and what to do instead.
     */
    static IParameterExceptionHandler handler() {
        return (ParameterException ex, String[] args) -> {

            final CommandLine command = ex.getCommandLine();
            final PrintWriter err = command.getErr();

            if (ex instanceof UnmatchedArgumentException unmatched
                    && !unmatched.getUnmatched().isEmpty()) {

                final String first = unmatched.getUnmatched().getFirst();
                final String where = command.getCommandSpec().qualifiedName(" ");

                if (first.startsWith("-")) {
                    err.println("sokar: '" + first + "' is not an option of '" + where + "'");
                } else {
                    err.println("sokar: '" + where + "' has no command called '" + first + "'");
                    // The whole point: a name that exists elsewhere is not a typo, and telling
                    // somebody where it lives is the one thing that gets them moving.
                    final Optional<String> elsewhere = pathTo(rootOf(command), first);
                    final Optional<String> nearest = nearestTo(rootOf(command), first);
                    if (elsewhere.isPresent()) {
                        err.println("sokar: it is '" + elsewhere.get() + "'");
                    } else if (nearest.isPresent()) {
                        err.println("sokar: did you mean '" + nearest.get() + "'?");
                    } else if (!available(command).isEmpty()) {
                        err.println("sokar: try one of: "
                                + String.join(", ", available(command)));
                    }
                }
            } else {
                // Everything else picocli says is already about what somebody typed rather than
                // about the parser - a missing parameter, a value of the wrong kind - so it is
                // passed through with the prefix the rest of this product uses.
                err.println("sokar: " + lower(ex.getMessage()));
            }

            err.println();
            err.println("Run '" + command.getCommandSpec().qualifiedName(" ")
                    + " --help' to see what it takes.");
            err.flush();
            return command.getCommandSpec().exitCodeOnInvalidInput();
        };
    }

    /**
     * Lowers a leading capital, so a message reads as part of the sentence it is prefixed onto.
     *
     * @param message Picocli's own wording.
     * @return The same, starting lower case.
     */
    private static String lower(String message) {
        if (message == null || message.isEmpty() || !Character.isUpperCase(message.charAt(0))) {
            return String.valueOf(message);
        }
        // Only a leading capital, and only when the word is not an acronym or a name - "URL"
        // and "SELinux" must survive.
        final String firstWord = message.split("\\s", 2)[0];
        return firstWord.equals(firstWord.toUpperCase(Locale.ROOT)) && firstWord.length() > 1
                ? message
                : Character.toLowerCase(message.charAt(0)) + message.substring(1);
    }
}
