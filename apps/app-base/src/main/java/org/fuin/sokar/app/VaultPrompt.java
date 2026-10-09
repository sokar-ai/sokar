package org.fuin.sokar.app;

import org.jspecify.annotations.Nullable;

/**
 * Asks a person for the vault passphrase, where there is a person at a terminal to ask.
 * <p>
 * Without one - a script, a pipe, the daemon, a watcher - nobody is asked and nothing waits for input: the command
 * refuses as it always did and names {@code sokar vault unlock}.
 */
@FunctionalInterface
public interface VaultPrompt {

    /** Nobody to ask: what every context a test builds has, unless it says otherwise. */
    VaultPrompt NOBODY = prompt -> null;

    /**
     * Asks once.
     *
     * @param prompt What the person is shown.
     * @return What they typed, or {@code null} when nobody can be asked.
     */
    char @Nullable [] ask(String prompt);

    /**
     * Returns the prompt of this process's terminal.
     *
     * @return A prompt that asks only when standard input and output are a terminal.
     */
    static VaultPrompt terminal() {
        return prompt -> {
            final java.io.Console console = System.console();
            // A console object alone is no terminal: since Java 22 one exists for a pipe too.
            return console == null || !console.isTerminal() ? null : console.readPassword("%s", prompt);
        };
    }
}
