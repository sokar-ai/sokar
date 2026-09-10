package org.fuin.sokar.acceptance;

import org.fuin.sokar.machines.Ssh;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What one scenario holds while it runs: the machine, the terminal on it, and the last thing a
 * script wrote.
 * <p>
 * <strong>Exists so that two glue classes act on one terminal.</strong> Cucumber makes a new
 * instance of every glue class per scenario, and without an object factory each would open its own
 * session - so a step in this kit and a step in an agent's own glue would be typing into different
 * shells. The picocontainer factory hands both the same {@code World}, which is the whole reason
 * it is on the classpath.
 * <p>
 * <strong>The terminal is the scenario's; the connection is the run's.</strong> See
 * {@link Machine#shared()} for why a connection per scenario got this suite refused by sshd.
 * <p>
 * <strong>A secret is remembered by the name of the variable it came from, never printed.</strong>
 * A scenario asserts that a credential was <em>not</em> echoed, and an assertion that fails by
 * printing the expected value would put the credential into the run log and the annotation on the
 * feature file. Every check here says which variable, and never what was in it.
 */
public final class World implements AutoCloseable {

    /** {@code ${NAME}} in a command, replaced from the runner's environment before it is sent. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    private @Nullable Terminal terminal;

    private Ssh.@Nullable Output output;

    private final Map<String, String> secrets = new LinkedHashMap<>();

    /** Constructor for the object factory, which makes one per scenario. */
    public World() {
        super();
    }

    /**
     * Returns the machine - the run's one connection, opened the first time any scenario asks.
     *
     * @return The machine.
     * @throws IOException If it cannot be reached.
     */
    public Machine machine() throws IOException {
        return Machine.shared();
    }

    /**
     * Returns the terminal a person has open, or fails if none was opened.
     *
     * @return The terminal.
     */
    public Terminal terminal() {
        if (terminal == null) {
            throw new AssertionError("No terminal is open: start with 'Given a terminal on the"
                    + " machine'");
        }
        return terminal;
    }

    /**
     * Replaces the terminal, closing the one before it.
     *
     * @param fresh The new terminal, or {@code null} for none.
     * @throws IOException If the old one cannot be closed.
     */
    public void terminal(@Nullable Terminal fresh) throws IOException {
        if (terminal != null) {
            terminal.close();
        }
        terminal = fresh;
    }

    /**
     * Tells whether a terminal is open.
     *
     * @return {@code true} if a person has one.
     */
    public boolean hasTerminal() {
        return terminal != null;
    }

    /**
     * Returns what the last script wrote, or fails if none ran.
     *
     * @return The output.
     */
    public Ssh.Output output() {
        if (output == null) {
            throw new AssertionError("No script has run yet: use 'When a script runs ...' first");
        }
        return output;
    }

    /**
     * Remembers what the last script wrote.
     *
     * @param last The output.
     */
    public void output(Ssh.Output last) {
        output = last;
    }

    /**
     * Returns the value of a variable in the runner's environment, remembering it as a secret.
     * <p>
     * Remembered so that a later step can assert it appeared nowhere, by name.
     *
     * @param variable The variable's name.
     * @return Its value.
     */
    public String secret(String variable) {
        final String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            throw new AssertionError("The environment variable " + variable + " is not set on"
                    + " the machine running this suite");
        }
        secrets.put(variable, value);
        return value;
    }

    /**
     * Tells whether a variable in the runner's environment has a value.
     *
     * @param variable The variable's name.
     * @return {@code true} if it is set and not blank.
     */
    public static boolean isSet(String variable) {
        final String value = System.getenv(variable);
        return value != null && !value.isBlank();
    }

    /**
     * Tells whether text contains the value of a variable, without saying what the value is.
     *
     * @param variable The variable's name, as given to {@link #secret}.
     * @param text Where to look.
     * @return {@code true} if the value is in the text.
     */
    public boolean contains(String variable, String text) {
        final String value = secrets.containsKey(variable) ? secrets.get(variable)
                : secret(variable);
        return text.contains(value);
    }

    /**
     * Replaces every {@code ${NAME}} in a command from the runner's environment.
     * <p>
     * For a model name or a version that CI chooses per run - a value a scenario should not
     * hardcode and a person would type. Not for a credential: a secret goes through
     * {@link #secret} and standard input, never into a command.
     *
     * @param command The command as written in the scenario.
     * @return The command as it should be typed.
     */
    public static String expand(String command) {
        final Matcher matcher = PLACEHOLDER.matcher(command);
        final StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            final String value = System.getenv(matcher.group(1));
            if (value == null) {
                throw new AssertionError("The scenario names ${" + matcher.group(1)
                        + "} and the environment running this suite does not set it");
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    /**
     * Closes what this scenario opened: its terminal. The connection is the run's and outlives it.
     *
     * @throws IOException If the terminal cannot be closed.
     */
    @Override
    public void close() throws IOException {
        terminal(null);
    }
}
