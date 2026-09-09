package org.fuin.sokar.acceptance;

/**
 * Quoting for a command that has to survive being passed through another shell.
 */
final class Shell {

    private Shell() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Single-quotes a command, so a shell interprets nothing in it.
     *
     * @param command The command.
     * @return The command, quoted.
     */
    static String quote(String command) {
        return "'" + command.replace("'", "'\\''") + "'";
    }
}
