package org.fuin.sokar.acceptance;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import java.io.IOException;

/**
 * Starting a task, and the state a scenario needs before one.
 * <p>
 * <strong>What, not how.</strong> A feature file says a task nobody is watching was started; which
 * flags produce that, and where the vault keeps its file, are decisions this kit makes and changes
 * without every agent's scenarios changing with it. A scenario that spells out
 * {@code cd ~/p && sokar task start --agent a --prompt hello} is not describing behaviour, it is
 * describing this class - and it goes stale the day a flag is renamed.
 * <p>
 * The exception, deliberately, is a scenario about the command line itself: what a person types at
 * a terminal and what comes back is the product's surface, so {@code I run "sokar doctor"} names
 * the command because the command is the subject.
 */
public class TaskSteps {

    /**
     * A prompt is what makes a run unattended.
     * <p>
     * Its content is irrelevant - every scenario using this expects a refusal before the agent is
     * ever asked anything - so it is a fixed word rather than a parameter that would read as
     * though it mattered.
     */
    private static final String PROMPT = "hello";

    private final World world;

    /**
     * Constructor with the scenario's state.
     *
     * @param world What the scenario holds.
     */
    public TaskSteps(World world) {
        this.world = world;
    }

    /**
     * Makes sure the vault has no credential under a name.
     * <p>
     * <strong>States a premise rather than assuming one.</strong> Whether a machine holds a
     * credential depends on what an earlier feature did, so a scenario about a task that cannot
     * authenticate has to say so itself. Left unsaid, the same scenario passed for the agent whose
     * provider nothing had stored and failed for the two whose provider it had - which was luck in
     * both directions.
     * <p>
     * Removing is idempotent by design: Sokar answers "nothing named x was in the vault" and exits
     * zero, because removing something that is not there leaves the wanted state.
     *
     * @param name Vault entry, usually the provider a task would authenticate to.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("the vault holds no credential for {string}")
    public void theVaultHoldsNoCredentialFor(String name) throws IOException {
        world.output(world.machine().run("sokar vault remove " + Shell.quote(name)));
    }

    /**
     * Starts a task in a project with nobody watching it.
     * <p>
     * Unattended is the mode that refuses rather than warns: nobody is there to read a warning, so
     * a run that cannot authenticate is stopped before the gate, the image and the container. An
     * attached task deliberately does not refuse - working inside the container by hand is what a
     * shell task is for.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a task nobody is watching is started in {string} for the {string} agent")
    public void aTaskNobodyIsWatchingIsStarted(String project, String agent) throws IOException {
        world.output(world.machine().run(startCommand(project, agent)));
    }

    /**
     * Builds the command that starts an unattended task.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent to run.
     * @return The command.
     */
    static String startCommand(String project, String agent) {
        return inProject(project) + " && sokar task start --agent " + Shell.quote(agent)
                + " --prompt " + Shell.quote(PROMPT);
    }

    /**
     * Asks what a task would do, without doing any of it.
     * <p>
     * Nothing is built, so this is what a scenario uses when the question is about what Sokar says
     * rather than about what it creates.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent the task would run.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a script asks what a task in {string} for the {string} agent would do")
    public void aScriptAsksWhatATaskWouldDo(String project, String agent) throws IOException {
        world.output(world.machine().run(planCommand(project, agent)));
    }

    /**
     * Builds the command that asks what a task would do.
     *
     * @param project Project directory in the operator's home.
     * @param agent Which agent the task would run.
     * @return The command.
     */
    static String planCommand(String project, String agent) {
        return inProject(project) + " && sokar task start --agent " + Shell.quote(agent)
                + " --dry-run --detach";
    }

    /**
     * Returns a change into a project in the operator's home.
     * <p>
     * <strong>Not quoted, and that is the point.</strong> {@code Shell.quote("$HOME/p")} produces
     * {@code '$HOME/p'}, which the far shell does not expand - so the command fails with "no such
     * file or directory" naming a path with a dollar sign in it, on a rented machine, minutes into
     * a run. A tilde has to be left bare to mean anything, so the name is checked instead of
     * quoted.
     *
     * @param project The project.
     * @return A cd into it.
     */
    private static String inProject(String project) {
        if (!project.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Not a project name this step will put in a shell "
                    + "command unquoted: '" + project + "'. Letters, digits, dot, dash and "
                    + "underscore only.");
        }
        return "cd ~/" + project;
    }

    /**
     * Reads the vault exactly as it lies on disk.
     * <p>
     * Where that is belongs here rather than in every agent's scenarios: a feature file naming
     * {@code ~/.local/share/sokar/vault.bin} is asserting about a path it has no opinion on, and
     * would have to be edited in three repositories the day the path moves.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @When("the vault file is read as it lies on disk")
    public void theVaultFileIsRead() throws IOException {
        world.output(world.machine().run("cat \"$HOME/.local/share/sokar/vault.bin\""));
    }

    /**
     * Tries to log in to an agent.
     * <p>
     * Asked rather than done: the scenarios using this are about an agent that declares no way to
     * log in at all, and the answer must come before anything is contacted.
     *
     * @param agent Which agent.
     * @throws IOException If the machine cannot be reached.
     */
    @When("logging in to the {string} agent is attempted")
    public void loggingInIsAttempted(String agent) throws IOException {
        world.output(world.machine().run("sokar vault login " + Shell.quote(agent)
                + " --dry-run"));
    }
}
