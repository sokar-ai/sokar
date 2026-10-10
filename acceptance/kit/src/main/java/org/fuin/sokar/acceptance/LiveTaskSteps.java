package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.sokar.machines.Ssh;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A task that really runs: starting it, what its container holds, and what it left behind.
 * <p>
 * The last stage of each agent repository, which proves what an operator gets rather than what the
 * code does. <strong>No step here names an agent.</strong> The one thing that differs - how an agent
 * is asked a question inside its container - is written in the scenario, as the script it is.
 * <p>
 * <strong>A secret never goes on a command line</strong>, which every process on the machine can
 * read. It reaches the machine on standard input, through the vault steps, and is searched for here
 * on the runner's side: what is searched is fetched, the secret is never sent.
 */
public class LiveTaskSteps {

    /** How long a start may take: it may build an image, and a rented machine is slow. */
    static final int START_SECONDS = 900;

    /** How long one command in the container may take - an agent answering a prompt among them. */
    static final int EXEC_SECONDS = 300;

    private static final Pattern CONTAINER = Pattern.compile("^container\\s+(\\S+)", Pattern.MULTILINE);

    private static final Pattern SIDECAR = Pattern.compile("^sidecar\\s+(\\S+)", Pattern.MULTILINE);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    private final World world;

    /**
     * Constructor with the scenario's state.
     *
     * @param world What the scenario holds.
     */
    public LiveTaskSteps(World world) {
        this.world = world;
    }

    /**
     * Removes every task a failed scenario started and left running.
     * <p>
     * A failing step skips the scenario's own clean-up, and its agent kept running into every scenario after it: on a
     * leased machine one left behind took the memory the next start needed, and three scenarios after it failed for
     * that alone (2026-10-04). Only a failed scenario's, and only what it started itself, so a passing
     * scenario's task is its own business. Best effort: a removal that fails must not hide why the scenario did.
     *
     * @param scenario The scenario that ended.
     */
    @After
    public void removeTheTasksOfAFailedScenario(final Scenario scenario) {
        if (!scenario.isFailed()) {
            return;
        }
        for (final World.Task started : world.started()) {
            try {
                world.machine().run("sokar task remove " + Shell.quote(started.container()) + " --force");
            } catch (final IOException | RuntimeException ex) {
                scenario.log("could not remove " + started.container() + ": " + ex.getMessage());
            }
        }
    }

    /**
     * Starts a task and leaves it running, with the agent's default provider.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a task is started in {string} for the {string} agent and left running")
    public void aTaskIsStarted(String project, String agent) throws IOException {
        started(world.run(startCommand(project, agent, null)));
    }

    /**
     * Starts a task of a name the scenario chooses and leaves it running.
     * <p>
     * For scenarios that share one project, and so one image: each names its own task, because a
     * start of a task that is already running is refused.
     *
     * @param task The task's name.
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a task called {string} is started in {string} for the {string} agent and left running")
    public void aNamedTaskIsStarted(String task, String project, String agent) throws IOException {
        started(world.run(startCommand(task, project, agent, null)));
    }

    /**
     * Starts a task and leaves it running, through a provider the scenario names.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @param provider Which provider its credential comes from.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a task is started in {string} for the {string} agent through {string} and left running")
    public void aTaskIsStartedThrough(String project, String agent, String provider) throws IOException {
        started(world.run(startCommand(project, agent, provider)));
    }

    /**
     * Builds the command that starts a task and leaves it running.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @param provider The provider, or {@code null} for the agent's default.
     * @return The command.
     */
    static String startCommand(String project, String agent, @Nullable String provider) {
        return startCommand(null, project, agent, provider);
    }

    /**
     * Builds the command that starts a task of a given name and leaves it running.
     *
     * @param task The task's name, or {@code null} for the default.
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @param provider The provider, or {@code null} for the agent's default.
     * @return The command.
     */
    static String startCommand(@Nullable String task, String project, String agent, @Nullable String provider) {
        // --clearance deny: a scenario must never raise a prompt on somebody's desktop and then wait for it.
        return "timeout " + START_SECONDS + " sokar task start "
                + (task == null ? "" : Shell.quote(TaskSteps.aName(task)) + " ") + "--project " + Shell.quote(TaskSteps.aName(project))
                + " --repository " + Shell.quote(TaskSteps.aName(project))
                + " --agent " + Shell.quote(agent)
                + (provider == null ? "" : " --provider " + Shell.quote(provider))
                + " --detach --clearance deny";
    }

    /**
     * Says why a start did not start a task, or nothing when it did.
     * <p>
     * The container line alone was enough, so a start that printed it and then failed - a hook, the agent - passed
     * every step after it.
     *
     * @param status What the start exited with.
     * @param said What it printed.
     * @return The reason, or {@code null}.
     */
    static @Nullable String notStarted(int status, String said) {
        if (task(said).isEmpty()) {
            return "the task did not start (exit " + status + ")";
        }
        return status == 0 ? null : "the task's container was made, but the start failed (exit " + status + ")";
    }

    private void started(Ssh.Output output) {
        world.output(output);
        final Optional<World.Task> task = task(output.all());
        final String refused = notStarted(output.status(), output.all());
        if (refused != null || task.isEmpty()) {
            throw new AssertionError(refused + ":\n" + world.redact(tail(output.all(), 12)));
        }
        world.task(task.get());
    }

    /**
     * Reads the container and the state directory out of what a start printed.
     *
     * @param said What {@code sokar task start} printed.
     * @return The task, or empty when no container was named.
     */
    static Optional<World.Task> task(String said) {
        final Matcher container = CONTAINER.matcher(said);
        if (!container.find()) {
            return Optional.empty();
        }
        final Matcher sidecar = SIDECAR.matcher(said);
        final Path parent = sidecar.find() ? Path.of(sidecar.group(1)).getParent() : null;
        return Optional.of(new World.Task(container.group(1), parent == null ? null : parent.toString(), said));
    }

    /**
     * Runs a script inside the task's container, as the agent's user.
     * <p>
     * {@code ${NAME}} in the script is handed to the container as an environment variable and
     * expanded there, never pasted into the command: a value carrying a quote would otherwise end
     * the argument and run whatever followed it. A secret is refused, because the variable is on
     * {@code podman exec}'s command line.
     *
     * @param script What to run, in {@code sh}.
     * @throws IOException If the machine cannot be reached.
     */
    @When("the task's container runs:")
    public void theTasksContainerRuns(String script) throws IOException {
        final Map<String, String> passed = new LinkedHashMap<>();
        final Matcher placeholder = PLACEHOLDER.matcher(script);
        while (placeholder.find()) {
            final String name = placeholder.group(1);
            if (world.remembers(name)) {
                throw new AssertionError(name + " holds a secret, and would be on a command line; a credential"
                        + " reaches a task through the vault, never through the scenario");
            }
            final String value = System.getenv(name);
            if (value == null) {
                throw new AssertionError("The scenario names ${" + name + "} and the environment running this"
                        + " suite does not set it");
            }
            passed.put(name, value);
        }
        world.output(world.run(execCommand(world.task().container(), script, passed)));
    }

    /**
     * Builds the command that runs a script in a container.
     *
     * @param container The container.
     * @param script The script, with its {@code ${NAME}} left for the container's shell.
     * @param environment What to hand the container.
     * @return The command.
     */
    static String execCommand(String container, String script, Map<String, String> environment) {
        final StringBuilder command = new StringBuilder("timeout " + EXEC_SECONDS + " podman exec");
        environment.forEach((name, value) -> command.append(" --env ").append(Shell.quote(name + "=" + value)));
        return command.append(' ').append(Shell.quote(container)).append(" sh -c ").append(Shell.quote(script))
                .toString();
    }

    /**
     * Asserts that the container's environment does not hold a secret.
     * <p>
     * A scheme that authenticates by handing the agent the real key has not failed loudly - it has
     * failed quietly, and only a real credential makes the leak searchable.
     *
     * @param variable The variable the secret came from.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task's container environment does not contain the value of {string}")
    public void theContainerEnvironmentDoesNotContain(String variable) throws IOException {
        final Ssh.Output output = world.run(execCommand(world.task().container(), "env", Map.of()));
        assertThat(output.status()).as("the container's environment could not be read:%n%s",
                world.redact(output.all())).isZero();
        assertThat(world.contains(variable, output.out()))
                .as("the container's environment holds the value of %s", variable).isFalse();
    }

    /**
     * Asserts that nothing the task left behind holds a secret, with or without line breaks in it.
     * <p>
     * Searched: what the start printed, and every readable file under Sokar's runtime directory and
     * the task's own state directory. At least one file must have been searched, or the check proves
     * nothing and says so.
     *
     * @param variable The variable the secret came from.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("no log the task left contains the value of {string}")
    public void noLogContains(String variable) throws IOException {
        final World.Task task = world.task();
        final Ssh.Output listing = world.run(logListing(task.state()));
        assertThat(listing.status()).as("the logs could not be read:%n%s", world.redact(listing.err())).isZero();
        final Map<String, String> files = files(listing.out());
        assertThat(files).as("no file was found to search, so this proves nothing").isNotEmpty();
        final List<String> leaked = leaks(files, text -> world.containsAcrossLines(variable, text));
        assertThat(world.containsAcrossLines(variable, task.said()))
                .as("what the start printed holds the value of %s", variable).isFalse();
        assertThat(leaked).as("these hold the value of %s, searched in %d files", variable, files.size()).isEmpty();
    }

    /**
     * Builds the command that prints every file a task may have logged to, one per line.
     * <p>
     * Each line is the path and the content, both in base64, so that no content can break the line
     * and no secret needs to leave the runner to be looked for.
     *
     * @param state The task's state directory, or {@code null}.
     * @return The command.
     */
    static String logListing(@Nullable String state) {
        return "for d in \"${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar\""
                + (state == null ? "" : " " + Shell.quote(state))
                + "; do [ -d \"$d\" ] && find \"$d\" -type f -readable; done | sort -u"
                + " | while IFS= read -r f; do printf '%s\\t%s\\n'"
                + " \"$(printf '%s' \"$f\" | base64 -w0)\" \"$(base64 -w0 < \"$f\")\"; done";
    }

    /**
     * Decodes what {@link #logListing} printed.
     *
     * @param listing The output.
     * @return Each file's content by its path.
     */
    static Map<String, String> files(String listing) {
        final Map<String, String> files = new LinkedHashMap<>();
        for (final String line : listing.split("\n")) {
            final int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            final Base64.Decoder decoder = Base64.getDecoder();
            files.put(new String(decoder.decode(line.substring(0, tab)), StandardCharsets.UTF_8),
                    new String(decoder.decode(line.substring(tab + 1)), StandardCharsets.UTF_8));
        }
        return files;
    }

    /**
     * Names the files whose content holds a secret, one file at a time so a value cannot appear to
     * span two of them.
     *
     * @param files Content by path.
     * @param holds Whether one file's content holds the secret.
     * @return The paths that do.
     */
    static List<String> leaks(Map<String, String> files, Predicate<String> holds) {
        final List<String> leaked = new ArrayList<>();
        files.forEach((path, content) -> {
            if (holds.test(content)) {
                leaked.add(path);
            }
        });
        return leaked;
    }

    /**
     * Stops the scenario unless it runs as an unprivileged user.
     * <p>
     * Rootless podman and one operator's own directories are what a task runs in, so a run as root
     * proves something else and says so nowhere: it passed every check but the broker's for two days,
     * because a leg connected as root and nothing objected.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @Given("the suite runs as an unprivileged user")
    public void theSuiteRunsUnprivileged() throws IOException {
        final Ssh.Output output = world.run("id -u");
        assertThat(output.status()).as("could not ask who this suite runs as:%n%s", output.all()).isZero();
        assertThat(output.out().strip()).as("this suite must run as an unprivileged user, not as root")
                .isNotEqualTo("0");
    }

    /**
     * Asserts that the agent's traffic went through the task's broker.
     * <p>
     * The one piece of evidence that the agent read what Sokar gave it: a setup file with a token in
     * it proves what Sokar wrote, and an agent that ignores it passes every check of that file. On
     * failure the end of the relay's log is attached, which is where a request that went elsewhere
     * shows.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task's broker saw a request")
    public void theBrokerSawARequest() throws IOException {
        final String state = world.task().state();
        if (state == null) {
            throw new AssertionError("the start named no state directory, so there is no broker log to read");
        }
        final Ssh.Output requests = world.run(brokerCommand(state));
        world.output(requests);
        if (requests.status() != 0 || requests.out().isBlank()) {
            final Ssh.Output relay = world.run("tail -n 5 " + Shell.quote(state + "/relay.log"));
            throw new AssertionError("nothing reached the broker - the agent did not use the endpoint it was"
                    + " given.\nvault.log:\n" + world.redact(requests.all()) + "\nrelay.log, last lines:\n"
                    + world.redact(relay.all()));
        }
    }

    /**
     * Builds the command that prints the requests a task's broker logged.
     *
     * @param state The task's state directory.
     * @return The command.
     */
    static String brokerCommand(String state) {
        return "grep -m 4 '^request ' " + Shell.quote(state + "/vault.log");
    }

    /**
     * Asserts that an installed agent says which version it installs.
     *
     * @param agent The agent.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the {string} agent says which version it installs")
    public void theAgentSaysWhichVersion(String agent) throws IOException {
        installed(agent);
    }

    /**
     * Asserts that an agent installs the version its own bill names for a component.
     * <p>
     * Asked of the machine, not of the build: two sources that cannot drift apart unnoticed.
     *
     * @param agent The agent.
     * @param bill Where its package installed its bill.
     * @param component The component in the bill that is the agent's CLI.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the {string} agent installs the version the bill at {string} names for {string}")
    public void theAgentInstallsWhatTheBillNames(String agent, String bill, String component) throws IOException {
        final String declared = installed(agent);
        final Ssh.Output read = world.run("cat " + Shell.quote(bill));
        assertThat(read.status()).as("the bill at %s could not be read:%n%s", bill, read.err()).isZero();
        assertThat(versionIn(read.out(), component)).as("the version the bill at %s names for %s", bill, component)
                .contains(declared);
    }

    /**
     * Asserts that an agent installs the version a variable names - the candidate an update built.
     *
     * @param agent The agent.
     * @param variable The variable naming the version expected.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the {string} agent installs the version in the environment variable {string}")
    public void theAgentInstallsTheVersionIn(String agent, String variable) throws IOException {
        final String expected = System.getenv(variable);
        if (expected == null || expected.isBlank()) {
            throw new AssertionError("The environment variable " + variable + " is not set on the machine"
                    + " running this suite");
        }
        assertThat(installed(agent)).as("the version the %s agent installs", agent).isEqualTo(expected);
    }

    private String installed(String agent) throws IOException {
        final Ssh.Output output = world.run("sokar agents --supply-chain");
        world.output(output);
        return installs(output.out(), agent).orElseThrow(() -> new AssertionError(
                "the installed " + agent + " agent does not say which version it installs:\n" + output.all()));
    }

    /**
     * Reads what one agent's row of {@code sokar agents --supply-chain} says it installs.
     * <p>
     * That agent's row, not the first {@code installs:}: a machine with two agents reported the other.
     *
     * @param supplyChain The output.
     * @param agent The agent.
     * @return The version, or empty when the row says nothing or installs nothing.
     */
    static Optional<String> installs(String supplyChain, String agent) {
        boolean inRow = false;
        for (final String line : supplyChain.split("\n")) {
            final String[] words = line.strip().split("\\s+");
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                inRow = words[0].equals(agent);
            } else if (inRow && words.length == 2 && "installs:".equals(words[0])) {
                return "nothing".equals(words[1]) ? Optional.empty() : Optional.of(words[1]);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads the version a CycloneDX bill names for a component, nested components included.
     *
     * @param bill The bill, as JSON.
     * @param component The component's name.
     * @return Its version, or empty when the bill does not name it.
     */
    static Optional<String> versionIn(String bill, String component) {
        final Object parsed = Json.parse(bill);
        return parsed instanceof Map<?, ?> root ? find(root.get("components"), component) : Optional.empty();
    }

    private static Optional<String> find(@Nullable Object components, String name) {
        if (!(components instanceof List<?> list)) {
            return Optional.empty();
        }
        for (final Object item : list) {
            if (item instanceof Map<?, ?> component) {
                if (name.equals(component.get("name")) && component.get("version") instanceof String version) {
                    return Optional.of(version);
                }
                final Optional<String> nested = find(component.get("components"), name);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static String tail(String text, int lines) {
        final String[] all = text.strip().split("\n");
        return String.join("\n", java.util.Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
    }

}
