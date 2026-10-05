package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.fuin.sokar.machines.Ssh;

/**
 * Whether Sokar shows an agent waiting for a person - the reading its declaration gives, asked of Sokar itself.
 * <p>
 * <strong>These steps read what Sokar says, never the screen.</strong> An agent repository proves its own
 * declaration at the version it pins by driving its agent to a question and asking whether Sokar now shows it
 * waiting - so a declaration that stopped matching fails that repository's leg, where the version was moved.
 * A step that matched the screen itself would prove the step, not the product. <strong>No step here names an
 * agent's wording</strong>: that is in the agent's own declaration and nowhere else.
 */
public class WaitingSteps {

    /** How long a reading may take to show: a screen is read every few seconds, and a task takes a moment to draw. */
    static final Duration SHOWS_WITHIN = Duration.ofSeconds(60);

    /** How long "not waiting" has to hold: a check that looked once would pass between two redraws. */
    static final Duration HOLDS_FOR = Duration.ofSeconds(8);

    private static final Duration POLL = Duration.ofSeconds(1);

    private final World world;

    /**
     * Constructor with the scenario's state.
     *
     * @param world What the scenario holds.
     */
    public WaitingSteps(World world) {
        this.world = world;
    }

    /**
     * Asserts that Sokar shows an attached agent waiting for a person.
     *
     * @param agent The agent, as a scenario names it.
     * @param task The task.
     * @param project The task's project.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("sokar shows the {string} agent in task {string} of {string} waiting for a person")
    public void showsWaiting(String agent, String task, String project) throws IOException {
        final Instant deadline = existsBy(project, task).plus(SHOWS_WITHIN);
        String status = "";
        while (Instant.now().isBefore(deadline)) {
            status = status(project, task);
            if (screen(status).startsWith("reads as waiting for you")) {
                return;
            }
            pause();
        }
        throw new AssertionError("Sokar did not show the " + agent + " agent waiting within "
                + SHOWS_WITHIN.toSeconds() + "s. If its screen shows a question, the agent's waiting declaration no"
                + " longer fits it. 'sokar task status' said:\n" + status);
    }

    /**
     * Asserts that Sokar shows an attached agent not waiting, and keeps showing that.
     *
     * @param agent The agent.
     * @param task The task.
     * @param project The task's project.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("sokar shows the {string} agent in task {string} of {string} not waiting for a person")
    public void showsNotWaiting(String agent, String task, String project) throws IOException {
        final Instant deadline = existsBy(project, task).plus(SHOWS_WITHIN);
        String status = "";
        Instant since = null;
        while (Instant.now().isBefore(deadline)) {
            status = status(project, task);
            final String screen = screen(status);
            assertThat(screen).as("the %s agent read as waiting when it asks nothing:%n%s", agent, status)
                    .doesNotStartWith("reads as waiting");
            if (screen.startsWith("reads as not waiting")) {
                since = since == null ? Instant.now() : since;
                if (!Instant.now().isBefore(since.plus(HOLDS_FOR))) {
                    return;
                }
            } else {
                since = null;
            }
            pause();
        }
        throw new AssertionError("Sokar did not settle on showing the " + agent + " agent not waiting within "
                + SHOWS_WITHIN.toSeconds() + "s - 'cannot say' is not 'not waiting'. 'sokar task status' said:\n"
                + status);
    }

    /**
     * Starts a named unattended task that has to end within a bound.
     *
     * @param task The task's name.
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @param seconds How long the whole run may take.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a task called {string} nobody is watching is started in {string} for the {string} agent and ends within {int} seconds")
    public void aNamedUnattendedTask(String task, String project, String agent, int seconds) throws IOException {
        final Ssh.Output output = world.run("timeout " + seconds + " " + TaskSteps.startCommand(project, agent)
                .replaceFirst("^sokar task start ", "sokar task start " + Shell.quote(TaskSteps.aName(task)) + " "));
        world.output(output);
        assertThat(output.status()).as("the run did not end within %ds:%n%s", seconds, world.redact(output.all()))
                .isNotEqualTo(ReadySteps.TIMED_OUT);
    }

    /**
     * Asserts what Sokar says a task's last run said last.
     *
     * @param task The task.
     * @param project The task's project.
     * @param said What it must have said.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("sokar says task {string} of {string} last said {string}")
    public void lastSaid(String task, String project, String said) throws IOException {
        final String status = status(project, task);
        assertThat(status).as("what 'sokar task status' said").containsPattern("(?m)^last said +" + java.util.regex.Pattern.quote(said) + "$");
    }

    /**
     * Waits for a task typed at a terminal to exist, and says when it did.
     * <p>
     * The step's own bound counts from then: before it, the task's image is still being built, which the
     * agent has no say in - measured, a scenario asked about a task that did not exist yet.
     *
     * @param project The task's project.
     * @param task The task.
     * @return When it existed.
     * @throws IOException If the machine cannot be reached.
     */
    private Instant existsBy(String project, String task) throws IOException {
        final Instant deadline = Instant.now().plusSeconds(LiveTaskSteps.START_SECONDS);
        while (true) {
            final Ssh.Output output = world.run("sokar task status "
                    + Shell.quote(ReadySteps.container(project, task)));
            if (output.status() == 0) {
                return Instant.now();
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("task " + task + " of " + project + " did not appear within "
                        + LiveTaskSteps.START_SECONDS + "s:\n" + output.all());
            }
            pause();
        }
    }

    /**
     * Asserts that Sokar has recorded a session for a task, which its next start continues.
     *
     * @param task The task.
     * @param project The task's project.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("sokar says task {string} of {string} has a session to continue")
    public void hasASession(String task, String project) throws IOException {
        final String status = status(project, task);
        assertThat(status).as("what 'sokar task status' said").containsPattern("(?m)^session +\\S+ - the next start continues it$");
    }

    /**
     * Asserts what the screen tmux draws in a task shows, within a bound.
     * <p>
     * For a suite that knows its agent's wording - the stub's - and never for a step an agent repository
     * reuses: those ask Sokar.
     *
     * @param task The task.
     * @param project The task's project.
     * @param shown What the screen must show.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the screen of task {string} of {string} shows {string}")
    public void theScreenShows(String task, String project, String shown) throws IOException {
        final String container = ReadySteps.container(project, task);
        final Instant deadline = Instant.now().plusSeconds(LiveTaskSteps.START_SECONDS);
        String screen = "";
        while (Instant.now().isBefore(deadline)) {
            final Ssh.Output pane = world.run("podman exec " + Shell.quote(container) + " tmux capture-pane -p -J");
            screen = pane.out();
            if (pane.status() == 0 && screen.contains(shown)) {
                return;
            }
            pause();
        }
        throw new AssertionError("The screen in " + container + " did not show \"" + shown + "\":\n" + screen);
    }

    private String status(String project, String task) throws IOException {
        final Ssh.Output output = world.run("sokar task status " + Shell.quote(ReadySteps.container(project, task)));
        assertThat(output.status()).as("'sokar task status' failed:%n%s", output.all()).isZero();
        return output.out();
    }

    /**
     * Picks the screen line out of what {@code sokar task status} printed.
     *
     * @param status Its output.
     * @return What follows {@code screen}, or "" when there is no such line.
     */
    static String screen(String status) {
        for (final String line : status.split("\n")) {
            if (line.startsWith("screen ")) {
                return line.substring("screen ".length()).strip();
            }
        }
        return "";
    }

    private static void pause() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while asking Sokar", ex);
        }
    }
}
