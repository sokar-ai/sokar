package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.fuin.sokar.machines.Ssh;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Whether an agent reached work without being asked anything, attached and unattended.
 * <p>
 * <strong>The check is "reached work, and nothing came first"</strong>, not "the dialogs we know about
 * are absent": the second passes the day a release adds a question, which is the day it exists for. So
 * the agent declares what reaching work looks like - a line of text and how long it may take - and the
 * step waits for it without typing anything. A question blocks the text; the step fails and shows the
 * screen. <strong>No step here names an agent</strong>: what an agent shows is read from the agent
 * itself, through its own {@code describe}.
 */
public class ReadySteps {

    /** How long an agent that declares no bound of its own may take to reach work. */
    static final Duration DEFAULT_BOUND = Duration.ofSeconds(120);

    private final World world;

    /**
     * Constructor with the scenario's state.
     *
     * @param world What the scenario holds.
     */
    public ReadySteps(World world) {
        this.world = world;
    }

    /**
     * What an agent declares it shows once it has reached work.
     *
     * @param text The text.
     * @param bound How long reaching it may take.
     */
    record Ready(String text, Duration bound) {
    }

    /**
     * Asserts that the agent in the open terminal reached work with nothing asked first.
     * <p>
     * Types nothing. An agent that declares no marker makes this fail saying it cannot tell - a check
     * that passed without knowing what to look for would be the quiet kind of wrong this exists to end.
     *
     * @param agent The agent, as a scenario names it.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the {string} agent reaches work without being asked anything")
    public void theAgentReachesWork(String agent) throws IOException {
        final Ready ready = declared(agent).orElseThrow(() -> new AssertionError("The " + agent
                + " agent declares no ready marker, so whether it reached work without being asked cannot be told."
                + " It declares one as 'session: ready_marker:' in its manifest."));
        world.terminal().await(ready.text(), ready.bound());
    }

    /**
     * Asserts that waiting for an agent to reach work fails, and that the screen shows what stopped it.
     * <p>
     * For proving the check itself, with an agent that asks something first: a check that has never
     * failed is not known to notice anything.
     *
     * @param agent The agent.
     * @param shown What the terminal must show instead - the question.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("waiting for the {string} agent to reach work fails, and the terminal shows {string}")
    public void waitingForTheAgentFails(String agent, String shown) throws IOException {
        try {
            theAgentReachesWork(agent);
        } catch (AssertionError expected) {
            assertThat(world.terminal().seen()).as("what stopped the agent").contains(shown);
            return;
        }
        throw new AssertionError("The " + agent + " agent reached work, although it was expected to ask \""
                + shown + "\" first");
    }

    /**
     * Starts an unattended task that has to end within a bound the scenario states.
     * <p>
     * A run parked at a question is one that does not end; waiting it out is how a suite hangs for an
     * hour and reports nothing. So it is stopped at the bound and fails, saying so.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @param seconds How long the whole run may take.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a task nobody is watching is started in {string} for the {string} agent and ends within {int} seconds")
    public void anUnattendedTaskEndsWithin(String project, String agent, int seconds) throws IOException {
        final Ssh.Output output = world.run(boundedCommand(project, agent, seconds));
        world.output(output);
        assertThat(output.status()).as("the run did not end within %ds - an agent waiting on a question looks"
                + " exactly like this; it said:%n%s", seconds, world.redact(output.all())).isNotEqualTo(TIMED_OUT);
    }

    /** What {@code timeout} exits with when it had to stop the command. */
    static final int TIMED_OUT = 124;

    /**
     * Builds the command that starts an unattended task under a bound.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @param seconds The bound.
     * @return The command.
     */
    static String boundedCommand(String project, String agent, int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("A bound is at least one second, not " + seconds);
        }
        return "timeout " + seconds + " " + TaskSteps.startCommand(project, agent);
    }

    /**
     * Reads what an installed agent declares, from its own description.
     *
     * @param agent The agent.
     * @return What it shows once at work, or empty when it declares nothing.
     * @throws IOException If the machine cannot be reached.
     */
    private Optional<Ready> declared(String agent) throws IOException {
        final Ssh.Output described = world.run("\"$(sokar agents | awk -v a=" + Shell.quote(agent)
                + " '$1 == a { print $NF }')\" describe");
        assertThat(described.status()).as("the %s agent could not describe itself:%n%s", agent, described.all())
                .isZero();
        return ready(described.out());
    }

    /**
     * Reads the ready marker out of an agent's description.
     *
     * @param describe What the agent's {@code describe} printed.
     * @return The marker and its bound, or empty when the agent declares none.
     */
    static Optional<Ready> ready(String describe) {
        final Object parsed = Json.parse(describe);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new AssertionError("An agent's description is a JSON object, not: " + describe);
        }
        final Map<?, ?> definition = root.get("definition") instanceof Map<?, ?> nested ? nested : root;
        final @Nullable Object text = definition.get("readyMarker");
        if (text == null || String.valueOf(text).isBlank()) {
            return Optional.empty();
        }
        final Duration bound = definition.get("readyWithinSeconds") instanceof Number seconds
                ? Duration.ofSeconds(seconds.longValue()) : DEFAULT_BOUND;
        return Optional.of(new Ready(String.valueOf(text), bound));
    }
}
