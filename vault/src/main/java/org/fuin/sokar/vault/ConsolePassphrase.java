package org.fuin.sokar.vault;

import java.io.Console;
import java.util.Optional;

/**
 * Asks the operator to type the passphrase.
 * <p>
 * The last tier, and the only one that always works. Returns empty when there is no terminal,
 * which is what happens under a hook, in CI, or from the daemon - all places where blocking
 * forever on a prompt nobody can see would be worse than failing.
 */
public class ConsolePassphrase implements PassphraseSource {

    private final String promptText;

    /**
     * Constructor.
     *
     * @param promptText What to show before reading.
     */
    public ConsolePassphrase(String promptText) {
        this.promptText = promptText;
    }

    @Override
    public Optional<char[]> passphrase() {
        final Console console = System.console();
        // isTerminal() as well: since Java 22 a Console exists when standard input is a pipe, and
        // switching echo off on a pipe fails with "Inappropriate ioctl for device" instead of
        // answering that there is nobody to ask.
        if (console == null || !console.isTerminal()) {
            return Optional.empty();
        }
        final char[] typed = console.readPassword("%s", promptText);
        return typed == null || typed.length == 0 ? Optional.empty() : Optional.of(typed);
    }

    @Override
    public String name() {
        return "prompt";
    }
}
