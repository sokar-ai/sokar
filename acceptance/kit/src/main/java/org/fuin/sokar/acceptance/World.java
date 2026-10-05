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

    private @Nullable Task task;

    private final java.util.List<Task> everyStarted = new java.util.ArrayList<>();

    private @Nullable String vault;

    private @Nullable String toolFailure;

    private boolean daemonWasRunning;

    private @Nullable String daemonUnit;

    /**
     * Every project the kit made in this run, in any scenario, by the account it was made under - removed once
     * the run is over, as that account.
     */
    private static final java.util.Map<String, java.util.Set<String>> PROJECTS = new java.util.LinkedHashMap<>();

    /**
     * A task this scenario started and left running.
     *
     * @param container The container it runs in.
     * @param state Its state directory on the machine, or {@code null} if the start did not name one.
     * @param said What the start printed, searched later for a secret like any log.
     */
    public record Task(String container, @Nullable String state, String said) {
    }

    /** Constructor for the object factory, which makes one per scenario. */
    public World() {
        super();
    }

    /**
     * Returns the machine - this scenario's account's connection, opened the first time any scenario asks.
     *
     * @return The machine.
     * @throws IOException If it cannot be reached.
     */
    public Machine machine() throws IOException {
        return Machine.shared();
    }

    /**
     * Runs a command with no terminal, in this scenario's environment.
     * <p>
     * <strong>Every step sends through here, never through the machine directly</strong>, so that a
     * scenario with a vault of its own has every command use it - a step that went around would act
     * on the account's vault while the scenario believed it was acting on its own.
     *
     * @param command What to run.
     * @return What it wrote and what it exited with.
     * @throws IOException If the machine cannot be reached.
     */
    public Ssh.Output run(String command) throws IOException {
        return machine().run(environment() + command);
    }

    /**
     * Runs a command with no terminal and standard input, in this scenario's environment.
     *
     * @param command What to run.
     * @param stdin What to feed it - how a secret reaches the machine.
     * @return What it wrote and what it exited with.
     * @throws IOException If the machine cannot be reached.
     */
    public Ssh.Output run(String command, String stdin) throws IOException {
        return machine().run(environment() + command, stdin);
    }

    /**
     * Opens a terminal in this scenario's environment.
     *
     * @return The terminal.
     * @throws IOException If it cannot be opened.
     */
    public Terminal openTerminal() throws IOException {
        return prepared(machine().terminal());
    }

    /**
     * Opens a terminal in this scenario's environment that echoes what is typed, as a person's does: at it, a
     * passphrase that appears on screen was echoed by the prompt and not by the kit.
     *
     * @return The terminal.
     * @throws IOException If it cannot be opened.
     */
    public Terminal openEchoingTerminal() throws IOException {
        return prepared(machine().echoingTerminal());
    }

    private Terminal prepared(Terminal terminal) throws IOException {
        terminal.showing(this::redact).watching(new TaskHistories(this));
        if (vault != null) {
            // A leading space keeps it out of the shell's history, as a person would type it.
            terminal.type(" " + environment().strip());
            terminal.drain();
        }
        return terminal;
    }

    /**
     * What every command of this scenario starts with: its own vault, when it has one.
     *
     * @return An {@code export} and a separator, or nothing.
     */
    String environment() {
        return vault == null ? "" : "export SOKAR_VAULT=" + Shell.quote(vault) + "; ";
    }

    /**
     * The vault this scenario brought, or {@code null} when it uses the account's.
     *
     * @return The path on the machine, or {@code null}.
     */
    public @Nullable String vault() {
        return vault;
    }

    /**
     * Gives this scenario a vault of its own, or takes it away.
     *
     * @param path The path on the machine, or {@code null} for the account's.
     */
    public void vault(@Nullable String path) {
        vault = path;
    }

    /**
     * Remembers the daemon this scenario runs, and whether the account's has to be started again.
     *
     * @param wasRunning {@code true} if the account's daemon was stopped for this scenario.
     * @param unit The scenario's daemon's unit, or {@code null} for none.
     */
    public void daemon(boolean wasRunning, @Nullable String unit) {
        daemonWasRunning = wasRunning;
        daemonUnit = unit;
    }

    /**
     * Tells whether the account's daemon was stopped for this scenario.
     *
     * @return {@code true} if it has to be started again.
     */
    public boolean daemonWasRunning() {
        return daemonWasRunning;
    }

    /**
     * The unit of the daemon this scenario runs.
     *
     * @return Its name, or {@code null} for none.
     */
    public @Nullable String daemonUnit() {
        return daemonUnit;
    }

    /**
     * Declares what a failed tool call of this scenario's agent shows, so that a wait stops once it repeats.
     *
     * @param marker Text the agent shows with every failed tool call, and only then.
     */
    public void toolFailure(String marker) {
        if (marker.isBlank()) {
            throw new IllegalArgumentException("a tool failure marker that is blank is on every line");
        }
        toolFailure = marker;
    }

    /**
     * Returns what a failed tool call of this scenario's agent shows.
     *
     * @return The marker, or {@code null} when the scenario declared none.
     */
    public @Nullable String toolFailure() {
        return toolFailure;
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
     * Tells whether text contains a secret even where a line break was put into it.
     * <p>
     * A value that reaches a log across a newline - wrapped output, two writes, a formatter breaking
     * a long line - matches nothing line by line, and the check reads as proof that nothing leaked.
     *
     * @param variable The variable's name, as given to {@link #secret}.
     * @param text Where to look.
     * @return {@code true} if the value is in the text, with or without its line breaks.
     */
    public boolean containsAcrossLines(String variable, String text) {
        return contains(variable, text) || contains(variable, text.replace("\r", "").replace("\n", ""));
    }

    /**
     * Tells whether a variable's value was taken as a secret in this scenario.
     *
     * @param variable The variable's name.
     * @return {@code true} if {@link #secret} returned it.
     */
    public boolean remembers(String variable) {
        return secrets.containsKey(variable);
    }

    /**
     * Replaces every secret this scenario holds with the name it came from.
     * <p>
     * For a failure message that has to show what a command printed: a command that leaked would
     * otherwise have its leak repeated into the run log by the assertion that caught it.
     *
     * @param text What a command printed.
     * @return The same text with each secret's value replaced by {@code <value of NAME>}.
     */
    public String redact(String text) {
        String redacted = text;
        for (final Map.Entry<String, String> secret : secrets.entrySet()) {
            redacted = redacted.replace(secret.getValue(), "<value of " + secret.getKey() + ">");
        }
        return redacted;
    }

    /**
     * Remembers a value as a secret without reading the environment, for a test of this class.
     *
     * @param variable The name it is known by.
     * @param value The value.
     */
    void remember(String variable, String value) {
        secrets.put(variable, value);
    }

    /**
     * Remembers a project the kit made, so it can be removed when the run is over.
     * <p>
     * Per run, not per scenario: some scenarios share a project on purpose - one restarts the machine,
     * the next asks what the task on it says - and removing it between them breaks the second.
     *
     * @param name The project's name and directory in the operator's home.
     */
    public void made(String name) {
        final String account = Accounts.forThisThread();
        synchronized (PROJECTS) {
            PROJECTS.computeIfAbsent(account, any -> new java.util.LinkedHashSet<>()).add(name);
        }
    }

    /**
     * Returns every project the kit made in this run, and forgets them.
     *
     * @return Their names by the account each was made under, in the order they were first made.
     */
    static java.util.Map<String, java.util.List<String>> takeProjects() {
        synchronized (PROJECTS) {
            final java.util.Map<String, java.util.List<String>> made = new java.util.LinkedHashMap<>();
            PROJECTS.forEach((account, names) -> made.put(account, java.util.List.copyOf(names)));
            PROJECTS.clear();
            return made;
        }
    }

    /**
     * Returns the task this scenario started, or fails if it started none.
     *
     * @return The task.
     */
    public Task task() {
        if (task == null) {
            throw new AssertionError("No task is running: start one with 'When a task is started in ...'");
        }
        return task;
    }

    /**
     * Remembers the task this scenario started.
     *
     * @param started The task.
     */
    public void task(Task started) {
        task = started;
        everyStarted.add(started);
    }

    /**
     * Returns every task this scenario started, the earlier ones too.
     *
     * @return The tasks, in the order they were started.
     */
    public java.util.List<Task> started() {
        return java.util.List.copyOf(everyStarted);
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
     * Puts a task's container name where a command says {@code {task}}.
     * <p>
     * A container name carries a timestamp and a run id, so a scenario cannot write it down; it names
     * the task this scenario started instead, as a person would copy it from {@code sokar task list}.
     *
     * {@code {state}} is the task's state directory on the machine, where its logs and helpers' files are.
     *
     * @param command The command as written in the scenario.
     * @param task The task this scenario started.
     * @return The command with the name in place, quoted for the shell.
     */
    public static String aboutTask(String command, Task task) {
        if (!command.contains("{task}") && !command.contains("{state}")) {
            throw new AssertionError("The step is about the task and the command does not say where: "
                    + command);
        }
        final String state = task.state();
        if (command.contains("{state}") && state == null) {
            throw new AssertionError("The command names the task's state directory and its start did not say"
                    + " where that is: " + command);
        }
        return command.replace("{task}", Shell.quote(task.container()))
                .replace("{state}", state == null ? "" : Shell.quote(state));
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
