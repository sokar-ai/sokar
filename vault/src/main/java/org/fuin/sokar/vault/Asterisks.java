package org.fuin.sokar.vault;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Reads a secret at a terminal, showing one {@code *} per character.
 * <p>
 * <strong>Why not {@code Console.readPassword}.</strong> It shows nothing at all, and nothing at
 * all is indistinguishable from a terminal that has hung - which is exactly what it looks like
 * through an interface that opened the session for somebody. The operator asked for the feedback
 * every other passphrase prompt in the world gives.
 * <p>
 * <strong>Why the echo is off rather than simply printing.</strong> The terminal is put into raw
 * mode with {@code stty}, so the characters themselves never appear; this writes the asterisks.
 * A terminal that cannot be put into raw mode is left to {@code readPassword}, which is silent but
 * safe - a prompt that fell back to echoing what was typed would write the secret to the
 * scrollback, and many terminals persist that to disk.
 */
final class Asterisks {

    private Asterisks() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** What a terminal sends for a backspace, and what it sends for the other one. */
    private static final int BACKSPACE = 8;

    private static final int DELETE = 127;

    private static final int RETURN = '\r';

    private static final int NEWLINE = '\n';

    private static final int END_OF_TEXT = 3;

    /**
     * Reads a secret, showing a star per character.
     *
     * @param prompt What to write before reading.
     * @return What was typed, or empty when the terminal cannot be put into raw mode or nothing
     *         was typed.
     */
    static Optional<char[]> read(final String prompt) {
        final String saved = stty("-g");
        if (saved == null || stty("raw", "-echo") == null) {
            return Optional.empty();
        }
        final StringBuilder typed = new StringBuilder();
        try {
            System.out.print(prompt);
            System.out.flush();
            while (true) {
                final int character = System.in.read();
                if (character < 0 || character == RETURN || character == NEWLINE) {
                    break;
                }
                if (character == END_OF_TEXT) {
                    // Ctrl-C in raw mode is a byte rather than a signal, so it has to be honoured
                    // here or the prompt would be unquittable.
                    typed.setLength(0);
                    break;
                }
                if (character == BACKSPACE || character == DELETE) {
                    if (typed.length() > 0) {
                        typed.setLength(typed.length() - 1);
                        // Back over the star, blank it, back again.
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                    continue;
                }
                typed.append((char) character);
                System.out.print('*');
                System.out.flush();
            }
        } catch (final IOException ex) {
            return Optional.empty();
        } finally {
            stty(saved);
            System.out.println();
            System.out.flush();
        }
        if (typed.isEmpty()) {
            return Optional.empty();
        }
        final char[] secret = new char[typed.length()];
        typed.getChars(0, typed.length(), secret, 0);
        // The builder is the copy that exists for no reason after this point.
        typed.setLength(0);
        return Optional.of(secret);
    }

    /**
     * Runs {@code stty} against this process's own terminal.
     *
     * @param arguments What to ask it.
     * @return Its first line of output, or {@code null} when it could not be run.
     */
    private static @org.jspecify.annotations.Nullable String stty(final String... arguments) {
        final java.util.List<String> command = new java.util.ArrayList<>();
        command.add("stty");
        command.addAll(Arrays.asList(arguments));
        try {
            final Process process = new ProcessBuilder(command)
                    // The terminal it must act on is this process's own, which is what
                    // INHERIT gives it - a pipe here would make stty answer about nothing.
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            final String answer = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.US_ASCII).strip();
            return process.waitFor() == 0 ? answer : null;
        } catch (final IOException ex) {
            return null;
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
