package org.fuin.sokar.acceptance;

import org.fuin.sokar.machines.Ssh;
import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.time.Duration;
import org.opentest4j.TestAbortedException;

/**
 * What a scenario can do to a machine, and what it can then say about it.
 * <p>
 * Two vocabularies, and the difference is the point: <em>a terminal on the machine</em> and
 * <em>I run</em> mean a person is watching; <em>a script runs</em> means nobody is. Half of what
 * these scenarios exist for is "does not ask when nobody is there", and a suite that only ever
 * allocates a pty tests one side of every one of them.
 * <p>
 * <strong>Complete on its own.</strong> A repository whose scenarios are made only of these steps
 * needs no glue class of its own; one that adds steps takes the same {@link World} in its
 * constructor and acts on the same terminal.
 */
public class TerminalSteps {

    private final World world;

    /**
     * Constructor with the scenario's state.
     *
     * @param world What the scenario holds.
     */
    public TerminalSteps(World world) {
        this.world = world;
    }

    /**
     * Closes what the scenario opened: its terminal.
     *
     * @throws IOException If it cannot be closed.
     */
    @After
    public void closeTheTerminal() throws IOException {
        world.close();
    }

    /**
     * Names the account a failed scenario ran under, in its report.
     * <p>
     * When features run in parallel, each under an account of its own, a failure caused by two accounts
     * meeting - a port, a file under {@code /etc} - reads like one caused by the scenario itself, unless the
     * report says which account it was and a second failure beside it names the other.
     *
     * @param scenario The scenario that ended.
     */
    @After
    public void nameTheAccountOfAFailure(Scenario scenario) {
        if (scenario.isFailed()) {
            scenario.log("ran as account " + Accounts.forThisThread() + " on thread "
                    + Thread.currentThread().getName());
        }
    }

    /**
     * Closes the run's connections once every scenario has had them.
     *
     * @throws IOException If it cannot be closed.
     */
    @AfterAll
    public static void closeTheConnection() throws IOException {
        try {
            removeTheProjectsTheKitMade();
        } finally {
            Machine.closeShared();
        }
    }

    /**
     * Removes every project the kit made in this run, passed or failed.
     * <p>
     * Here although tearing down is not, because this removes only what the kit itself made: the
     * repository it wrote into the operator's home and the following of it. Measured on 2026-09-27,
     * three scenarios of the agent repositories left their project followed and every one left its
     * directory, on both VMs, until an agent removed them by hand. Once, at the end of the run: a
     * project two scenarios share must outlive the first. {@code unfollow --force} removes the
     * project's tasks with it and is best effort, so a renamed verb cannot turn a run red.
     *
     * @throws IOException If the machine cannot be reached.
     */
    static void removeTheProjectsTheKitMade() throws IOException {
        for (final var made : World.takeProjects().entrySet()) {
            // As the account that made them, named rather than taken from the thread: this runs on
            // whichever thread ends the run, which need not be one that held an account.
            final Machine machine = Machine.forAccount(made.getKey());
            for (final String name : made.getValue()) {
                machine.run("sokar project unfollow " + Shell.quote(name) + " --force");
                machine.run("rm -rf -- ~/" + Shell.quote(name));
            }
        }
    }

    /**
     * Skips the scenario unless the runner's environment carries a value.
     * <p>
     * For the half of a suite that needs a real credential: a fork or a machine without the
     * secret gets fewer scenarios rather than a red run that says nothing about the product.
     * Skipped, not passed, so the summary shows what was not proved.
     *
     * @param variable The variable's name.
     */
    @Given("the environment variable {string} is set")
    public void theEnvironmentVariableIsSet(String variable) {
        if (!World.isSet(variable)) {
            throw new TestAbortedException("Skipped: " + variable
                    + " is not set where this suite runs");
        }
    }

    /**
     * Closes the terminal, as a person closing their laptop would.
     *
     * @throws IOException If it cannot be closed.
     */
    @When("I log off")
    public void iLogOff() throws IOException {
        // The channel, not the machine: what is being tested is that the far end does not care.
        world.terminal(null);
    }

    /**
     * Opens a terminal with a known prompt.
     *
     * @throws IOException If it cannot be opened.
     */
    @Given("a terminal on the machine")
    public void aTerminal() throws IOException {
        final Terminal terminal = world.openTerminal();
        world.terminal(terminal);
        // A prompt of our own, so that what a scenario waits for afterwards is its command's
        // output rather than whatever the login banner happened to say.
        // Both stated rather than inherited: a shell opened over ssh may source no profile, so
        // an unprivileged install in ~/.local/bin would not be found - which is how this suite
        // answered 127 to every command on its first run against a rented machine.
        terminal.type("export PATH=\"$HOME/.local/bin:$PATH\"; export PS1='ready$ '");
        terminal.await("ready$");
    }

    /**
     * Types a command and presses enter.
     * <p>
     * {@code ${NAME}} in the command is replaced from the runner's environment first, for a value
     * CI chooses per run - a model, a version. Never for a credential: that goes through
     * {@link #iTypeTheValueOf}.
     *
     * @param command What to run.
     * @throws IOException If it cannot be sent.
     */
    @When("I run {string}")
    public void iRun(String command) throws IOException {
        world.terminal().type(World.expand(command));
        // Not "wait for the prompt": a command that asks a question never reaches one, and
        // waiting for it would hang on exactly the scenarios this module exists for.
        world.terminal().drain();
    }

    /**
     * Types a line, for answering whatever the last command asked.
     *
     * @param line What to type.
     * @throws IOException If it cannot be sent.
     */
    @When("I type {string}")
    public void iType(String line) throws IOException {
        world.terminal().type(line);
        world.terminal().drain();
    }

    /**
     * Types text and presses Enter, for a program that reads the keyboard itself - an agent's own
     * interface, which submits on Enter and takes {@code I type}'s line feed as a new line in its
     * prompt. See {@link Terminal#enter}.
     *
     * @param text What to type.
     * @throws IOException If it cannot be sent.
     */
    @When("I enter {string}")
    public void iEnter(String text) throws IOException {
        world.terminal().enter(text);
        world.terminal().drain();
    }

    /**
     * Presses Enter alone, as a person confirming what a program shows.
     *
     * @throws IOException If it cannot be sent.
     */
    @When("I press Enter")
    public void iPressEnter() throws IOException {
        world.terminal().enter("");
        world.terminal().drain();
    }

    /**
     * Types the value of a variable in the runner's environment, as a person pasting a secret.
     * <p>
     * The terminal has echo off, so the value is not expected back - and
     * {@link #theTerminalDoesNotShowTheValueOf} is how a scenario proves the far end did not echo
     * it either. The value is remembered by the variable's name and never printed.
     *
     * @param variable The variable's name.
     * @throws IOException If it cannot be sent.
     */
    @When("I type the value of {string}")
    public void iTypeTheValueOf(String variable) throws IOException {
        world.terminal().type(world.secret(variable));
        world.terminal().drain();
    }

    /**
     * Waits for the task's session prompt after attaching.
     *
     * @throws IOException If the terminal cannot be read.
     */
    @When("I wait for the session inside the container")
    public void iWaitForTheSession() throws IOException {
        // 'task attach' goes through tmux, which does NOT set Sokar's prompt - only the shell
        // that 'task run --attach' opens does. Found by this suite, and it is a real
        // inconsistency: the prompt naming the task is there when you start one and gone when
        // you come back to it. Until that is decided this waits for what the container actually
        // shows rather than for what it ought to.
        world.terminal().await("agent@", Terminal.BUILD_PATIENCE);
    }

    /**
     * Waits for the task's own prompt, which is how a scenario knows it is inside.
     *
     * @throws IOException If the terminal cannot be read.
     */
    @When("I wait for the shell inside the container")
    public void iWaitForTheShellInside() throws IOException {
        // The long patience is here and nowhere else: this is the step behind which an image
        // gets built, and only the first one pays it.
        world.terminal().await("sokar[", Terminal.BUILD_PATIENCE);
    }

    /**
     * Waits for text, with the ordinary patience.
     *
     * @param text What must appear.
     * @throws IOException If the terminal cannot be read.
     */
    @Then("the terminal shows {string}")
    public void theTerminalShows(String text) throws IOException {
        world.terminal().await(text);
        assertThat(world.terminal().seen()).contains(text);
    }

    /**
     * Waits for text, for as long as a scenario says.
     * <p>
     * For the one step behind which a model answers: longer than a prompt, shorter than a build,
     * and stated in the scenario so that a slow expectation is a visible choice.
     *
     * @param seconds How long to allow.
     * @param text What must appear.
     * @throws IOException If the terminal cannot be read.
     */
    @Then("within {int} seconds the terminal shows {string}")
    public void withinSecondsTheTerminalShows(int seconds, String text) throws IOException {
        world.terminal().await(text, Duration.ofSeconds(seconds));
        assertThat(world.terminal().seen()).contains(text);
    }

    /**
     * Asserts text has not appeared.
     *
     * @param text What must be absent.
     * @throws IOException If the terminal cannot be read.
     */
    @Then("the terminal does not show {string}")
    public void theTerminalDoesNotShow(String text) throws IOException {
        world.terminal().drain();
        assertThat(world.terminal().seen()).doesNotContain(text);
    }

    /**
     * Asserts a secret was not echoed, without saying what it is.
     *
     * @param variable The variable the secret came from.
     * @throws IOException If the terminal cannot be read.
     */
    @Then("the terminal does not show the value of {string}")
    public void theTerminalDoesNotShowTheValueOf(String variable) throws IOException {
        world.terminal().drain();
        // A boolean, on purpose: an assertion that fails by printing the expected text would put
        // the credential into the run log and the annotation on the feature file.
        assertThat(world.contains(variable, world.terminal().seen()))
                .as("the terminal showed the value of %s", variable).isFalse();
    }

    /**
     * Creates a small git project on the machine.
     *
     * @param name The project's name and directory.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("a project called {string} with a file in it")
    public void aProject(String name) throws IOException {
        aProjectOfClass(name, "offline");
    }

    /**
     * Creates a small git project on the machine, in a security class the scenario chooses.
     *
     * @param name The project's name and directory.
     * @param securityClass Its class: {@code offline}, {@code guarded} or {@code online}.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("a project called {string} of class {string} with a file in it")
    public void aProjectOfClass(String name, String securityClass) throws IOException {
        aProjectSaying(name, securityClass, "");
    }

    /**
     * Creates a small git project on the machine whose project file says more than its name, class
     * and base image - the egress sets it declares, a snippet its image runs.
     *
     * @param name The project's name and directory.
     * @param securityClass Its class: {@code offline}, {@code guarded} or {@code online}.
     * @param more YAML appended to the project file, right after its image block's {@code base_image}:
     *        indented by two spaces it continues that block (a {@code snippet}), unindented it starts
     *        its own ({@code egress}).
     * @throws IOException If the machine cannot be reached.
     */
    @Given("a project called {string} of class {string} whose project file also says:")
    public void aProjectSaying(String name, String securityClass, String more) throws IOException {
        // Built by running the commands rather than by writing files from here: a fixture the
        // suite creates is a fixture that can be right while the product is wrong.
        // Built by running the commands rather than by writing files from here: a fixture the
        // suite creates is a fixture that can be right while the product is wrong.
        //
        // A project comes to be on a machine by that machine FOLLOWING its repository - nothing
        // writes a project file any more. So the repository is made here, on the machine, and
        // followed from a local path: a rented machine has no forge to push a fixture to, and a
        // public one would be a dependency on somebody else's uptime.
        //
        // Followed unverified, or every fixture would have to sign its commits.
        final Ssh.Output owned = world.run(fixtureCheck(projectName(name)));
        if (owned.status() != 0) {
            throw new AssertionError(owned.all().strip());
        }
        // Only now: a directory the check refused must not be the cleanup's to delete either.
        world.made(name);
        world.run("rm -rf ~/" + name + " && mkdir -p ~/" + name);
        world.run("cd ~/" + name + " && git init -q -b main . && touch .git/" + FIXTURE + " "
                + "&& git config user.email t@example.com && git config user.name T "
                + "&& echo 'the project' > README.md");
        world.run("cd ~/" + name + " && printf '%s\\n' "
                + "'project:' '  name: \"" + name + "\"' '  security_class: \"" + securityClass
                + "\"' "
                + "'image:' '  base_image: \"ubuntu:24.04\"' > project.yml");
        if (!more.isBlank()) {
            world.run("cd ~/" + name + " && printf '%s\\n' " + more.lines().map(Shell::quote)
                    .collect(java.util.stream.Collectors.joining(" ")) + " >> project.yml");
        }
        world.run("cd ~/" + name + " && git add -A && git commit -q -m initial");
        final Ssh.Output followed = world.run("sokar project follow " + name + " ~/" + name + " --unverified");
        // Said here rather than at the first task start, which is minutes later and reads as a broken build.
        assertThat(followed.status()).as("following %s failed:%n%s", name, followed.all()).isZero();
    }

    /** Left inside a fixture's .git, never committed: what says a directory is one a run made. */
    static final String FIXTURE = "sokar-acceptance-fixture";

    /**
     * Builds the check that stops the step from deleting a directory no acceptance run made.
     * <p>
     * The step used to begin with {@code rm -rf ~/NAME}, so a person's own project that shared a
     * scenario's name was deleted the moment a scenario named it. Asked on 2026-09-27; a leftover of
     * an aborted run still carries the marker and is cleared.
     *
     * @param name The project's name, already checked.
     * @return A command that exits non-zero, saying why, when the directory is somebody else's.
     */
    static String fixtureCheck(String name) {
        final String home = "~/" + Shell.quote(name);
        return "if [ -e " + home + " ] && [ ! -e " + home + "/.git/" + FIXTURE + " ]; then echo 'refused: " + name
                + " is in the home and no acceptance run made it - a project of a person is not a fixture';"
                + " exit 1; fi";
    }

    /**
     * Refuses a project name that is not one, before it goes into a path that is deleted.
     *
     * @param name The name a scenario gave.
     * @return The same name.
     */
    static String projectName(String name) {
        final String checked = TaskSteps.aName(name);
        if (checked.chars().allMatch(c -> c == '.')) {
            throw new IllegalArgumentException("Not a project name: '" + name + "' names a directory, not a project");
        }
        return checked;
    }

    /**
     * Reboots the machine and waits for it to answer again.
     *
     * @throws IOException If it never comes back.
     */
    @When("the machine restarts")
    public void theMachineRestarts() throws IOException {
        world.terminal(null);
        world.machine().restart().restart();
    }

    /**
     * Runs a command with no terminal.
     *
     * @param command What to run, with {@code ${NAME}} expanded from the runner's environment.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a script runs {string}")
    public void aScriptRuns(String command) throws IOException {
        world.output(world.run(World.expand(command)));
    }

    /**
     * Runs a command with no terminal about the task this scenario started.
     *
     * @param command What to run, with {@code {task}} where the task's container name goes.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a script runs {string} about the task")
    public void aScriptRunsAboutTheTask(String command) throws IOException {
        world.output(world.run(World.aboutTask(World.expand(command), world.task())));
    }

    /**
     * Runs a script of several lines with no terminal, in {@code bash}.
     * <p>
     * For a check that needs a file made first or two commands compared - which a scenario would
     * otherwise spread over steps that share nothing but the machine.
     *
     * @param script What to run, with {@code ${NAME}} expanded from the runner's environment.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a script runs:")
    public void aScriptRunsLines(String script) throws IOException {
        world.output(world.run("bash -c " + Shell.quote(World.expand(script))));
    }

    /**
     * Runs a script of several lines with no terminal about the task this scenario started.
     *
     * @param script What to run, with {@code {task}} and {@code {state}} where the task's container
     *        name and its state directory go.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a script runs about the task:")
    public void aScriptRunsLinesAboutTheTask(String script) throws IOException {
        world.output(world.run("bash -c " + Shell.quote(World.aboutTask(World.expand(script), world.task()))));
    }

    /**
     * Runs a command with no terminal and a secret on its standard input.
     * <p>
     * How a credential reaches a vault from a script: never on the command line, which every
     * process on the machine can read.
     *
     * @param command What to run.
     * @param variable The variable whose value is fed to it.
     * @throws IOException If the machine cannot be reached.
     */
    @When("a script runs {string} with the value of {string} on standard input")
    public void aScriptRunsWithStdin(String command, String variable) throws IOException {
        world.output(world.run(World.expand(command), world.secret(variable)));
    }

    /**
     * Runs a command and asserts on what it wrote, in one step.
     *
     * @param command What to run.
     * @param text What must be in its output.
     * @throws IOException If the machine cannot be reached.
     */
    @Then("a script running {string} mentions {string}")
    public void aScriptRunningMentions(String command, String text) throws IOException {
        final Ssh.Output output = world.run(World.expand(command));
        world.output(output);
        assertThat(output.all()).as("running: %s", command).contains(text);
    }

    /** Asserts the last script failed. */
    @Then("it exits non-zero")
    public void itExitsNonZero() {
        assertThat(world.output().status()).isNotZero();
    }

    /** Asserts the last script succeeded. */
    @Then("it exits zero")
    public void itExitsZero() {
        assertThat(world.output().status()).as("output was:%n%s", world.output().all()).isZero();
    }

    /**
     * Asserts the last script wrote something.
     *
     * @param text What must be there.
     */
    @Then("its output contains {string}")
    public void itsOutputContains(String text) {
        assertThat(world.output().all()).contains(text);
    }

    /**
     * Asserts the last script did not write something.
     *
     * @param text What must be absent.
     */
    @Then("its output does not contain {string}")
    public void itsOutputDoesNotContain(String text) {
        assertThat(world.output().all()).doesNotContain(text);
    }

    /**
     * Asserts the last script did not write a secret, without saying what it is.
     *
     * @param variable The variable the secret came from.
     */
    @Then("its output does not contain the value of {string}")
    public void itsOutputDoesNotContainTheValueOf(String variable) {
        assertThat(world.contains(variable, world.output().all()))
                .as("the output contained the value of %s", variable).isFalse();
    }

    /**
     * Asserts the last script wrote one of several honest answers.
     * <p>
     * For a fact with several shapes. Asserting one of them would make the scenario depend on
     * which machine it ran on, which is how a suite teaches people to re-run it.
     *
     * @param alternatives Comma-separated texts, any one of which satisfies the step.
     */
    @Then("its output mentions one of {string}")
    public void itsOutputMentionsOneOf(String alternatives) {
        final java.util.List<String> any = java.util.Arrays.stream(alternatives.split(","))
                .map(String::strip).toList();
        final String all = world.output().all();
        assertThat(any).as("output was:%n%s", all).anyMatch(all::contains);
    }

    /**
     * Asserts the last script wrote a line that matches a pattern, for a report whose columns are
     * padded to whatever the widest entry was.
     *
     * @param pattern A regular expression one whole line must match.
     */
    @Then("its output has a line matching {string}")
    public void itsOutputHasALineMatching(String pattern) {
        final java.util.regex.Pattern line = java.util.regex.Pattern.compile(pattern);
        final String all = world.output().all();
        assertThat(all.lines().map(String::strip).anyMatch(each -> line.matcher(each).matches()))
                .as("no line matches %s in:%n%s", pattern, all).isTrue();
    }

    /** Asserts the last script painted nothing - the other half of every color scenario. */
    @Then("its output contains no escape sequences")
    public void itsOutputHasNoEscapes() {
        // A suite that only ever allocates a pty proves that color appears and never that it
        // stays out of a pipe, a log or a fixture.
        assertThat(world.output().all()).doesNotContain("\033[");
    }
}
