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

    /** How long the marker has to stay on the screen: a prompt drawn and then covered by a dialog is not work. */
    static final Duration STEADY = Duration.ofSeconds(3);

    /** How often the screen is read. */
    private static final Duration POLL = Duration.ofMillis(500);

    /** The last screen read, for a failure to show and for the step that expects one. */
    private String screen = "";

    /**
     * Asserts that the agent attached in a task reached work with nothing asked first.
     * <p>
     * <strong>Read from the screen tmux renders in the task, not from the stream.</strong> An agent that
     * draws its prompt and then a dialog over it leaves the prompt's text in the stream while the screen
     * shows only the dialog - measured with a real agent, whose setup wizard passed a stream check. So the
     * pane is read as tmux draws it, and the marker has to <em>stay</em> there for {@link #STEADY}: seen
     * once, between the prompt being drawn and the dialog covering it, it would pass the same way.
     * <p>
     * Types nothing. An agent that declares no marker makes this fail saying it cannot tell - a check
     * that passed without knowing what to look for would be the quiet kind of wrong this exists to end.
     *
     * @param agent The agent, as a scenario names it.
     * @param task The task it was started in.
     * @param project The task's project.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the {string} agent in task {string} of {string} reaches work without being asked anything")
    public void theAgentReachesWork(String agent, String task, String project) throws IOException {
        final Ready ready = declared(agent).orElseThrow(() -> new AssertionError("The " + agent
                + " agent declares no ready marker, so whether it reached work without being asked cannot be told."
                + " It declares one as 'session: ready_marker:' in its manifest."));
        final String container = container(project, task);
        final Steady steady = new Steady(ready.text(), STEADY);
        // The agent's bound counts from its session existing, not from the step: before that the task is
        // still being built and started, which the agent has no say in - measured, a first image build
        // took most of a 30-second bound. Until then a start may take what any start may.
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(LiveTaskSteps.START_SECONDS);
        boolean session = false;
        while (true) {
            final java.util.Optional<String> pane = capture(container);
            if (pane.isPresent() && !session) {
                session = true;
                deadline = java.time.Instant.now().plus(ready.bound());
            }
            screen = pane.orElse("");
            if (steady.seen(screen, java.time.Instant.now())) {
                return;
            }
            if (java.time.Instant.now().isAfter(deadline)) {
                throw new AssertionError(!session
                        ? "No session to read appeared in " + container + " within " + LiveTaskSteps.START_SECONDS + "s"
                        : "The " + agent + " agent did not show \"" + ready.text() + "\" for " + STEADY.toSeconds()
                                + "s on end within " + ready.bound().toSeconds() + "s of its session starting."
                                + " The screen in " + container + ":\n" + screen);
            }
            pause();
        }
    }

    /**
     * Asserts that waiting for an agent to reach work fails, and that the screen shows what stopped it.
     * <p>
     * For proving the check itself, with an agent that asks something first: a check that has never
     * failed is not known to notice anything.
     *
     * @param agent The agent.
     * @param task The task it was started in.
     * @param project The task's project.
     * @param shown What the screen must show instead - the question.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("waiting for the {string} agent in task {string} of {string} to reach work fails, and the screen shows {string}")
    public void waitingForTheAgentFails(String agent, String task, String project, String shown) throws IOException {
        try {
            theAgentReachesWork(agent, task, project);
        } catch (AssertionError expected) {
            assertThat(screen).as("what stopped the agent").contains(shown);
            return;
        }
        throw new AssertionError("The " + agent + " agent reached work, although it was expected to ask \""
                + shown + "\" first");
    }

    /**
     * Names a task's container, as Sokar names it.
     *
     * @param project The project.
     * @param task The task.
     * @return The container's name.
     */
    static String container(String project, String task) {
        return "sokar-" + TaskSteps.aName(project) + "-" + TaskSteps.aName(task);
    }

    /**
     * Reads the screen tmux draws in a task, as text: joined lines, no colour, spaces as spaces.
     *
     * @param container The task's container.
     * @return The screen, or empty while there is no session to read yet.
     * @throws IOException If the machine cannot be reached.
     */
    private java.util.Optional<String> capture(String container) throws IOException {
        final Ssh.Output pane = world.run("podman exec " + Shell.quote(container) + " tmux capture-pane -p -J");
        return pane.status() == 0 ? java.util.Optional.of(pane.out()) : java.util.Optional.empty();
    }

    private static void pause() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the agent", ex);
        }
    }

    /**
     * Whether a text has stayed on the screen for long enough.
     */
    static final class Steady {

        private final String text;

        private final Duration duration;

        private java.time.@Nullable Instant since;

        /**
         * Constructor.
         *
         * @param text What has to stay.
         * @param duration For how long.
         */
        Steady(String text, Duration duration) {
            this.text = text;
            this.duration = duration;
        }

        /**
         * Takes one reading of the screen.
         *
         * @param screen What the screen shows now.
         * @param now When it was read.
         * @return {@code true} once the text has been on every reading for the whole duration.
         */
        boolean seen(String screen, java.time.Instant now) {
            if (!screen.contains(text)) {
                since = null;
                return false;
            }
            if (since == null) {
                since = now;
            }
            return !now.isBefore(since.plus(duration));
        }
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
        endsWithin(boundedCommand(project, agent, seconds), seconds);
    }

    /**
     * Starts an unattended task, served by a provider the scenario names, that has to end within a bound.
     * <p>
     * For an agent whose default provider the suite holds no key for: a run that ends at once on a
     * refused credential passes any bound and proves nothing about a question.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @param provider Which provider serves it.
     * @param seconds How long the whole run may take.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a task nobody is watching is started in {string} for the {string} agent through {string} and ends within {int} seconds")
    public void anUnattendedTaskThroughEndsWithin(String project, String agent, String provider, int seconds)
            throws IOException {
        endsWithin(boundedCommand(project, agent, seconds) + " --provider " + Shell.quote(TaskSteps.aName(provider)),
                seconds);
    }

    private void endsWithin(String command, int seconds) throws IOException {
        final Ssh.Output output = world.run(command);
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
