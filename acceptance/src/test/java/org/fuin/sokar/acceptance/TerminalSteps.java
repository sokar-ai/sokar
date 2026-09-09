package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;

/**
 * What a scenario can do to a machine, and what it can then say about it.
 */
public class TerminalSteps {

    private Machine machine;

    private Terminal terminal;

    private Machine.Output output;

    @After
    public void close() throws IOException {
        if (terminal != null) {
            terminal.close();
            terminal = null;
        }
        if (machine != null) {
            machine.close();
            machine = null;
        }
    }

    private Machine machine() throws IOException {
        if (machine == null) {
            machine = new Machine();
        }
        return machine;
    }

    @When("I log off")
    public void iLogOff() throws IOException {
        // The channel, not the machine: this is somebody closing their laptop, and what is being
        // tested is that the far end does not care.
        terminal.close();
        terminal = null;
    }

    @Given("a terminal on the machine")
    public void aTerminal() throws IOException {
        if (terminal != null) {
            terminal.close();
        }
        terminal = machine().terminal();
        // A prompt of our own, so that what a scenario waits for afterwards is its command's
        // output rather than whatever the login banner happened to say.
        terminal.type("export PS1='ready$ '");
        terminal.await("ready$");
    }

    @When("I run {string}")
    public void iRun(String command) throws IOException {
        terminal.type(command);
        // Not "wait for the prompt": a command that asks a question never reaches one, and
        // waiting for it would hang on exactly the scenarios this module exists for.
        terminal.drain();
    }

    @When("I type {string}")
    public void iType(String line) throws IOException {
        terminal.type(line);
        terminal.drain();
    }

    @When("I wait for the session inside the container")
    public void iWaitForTheSession() throws IOException {
        // 'task attach' goes through tmux, which does NOT set Sokar's prompt - only the shell
        // that 'task run --attach' opens does. Found by this suite, and it is a real
        // inconsistency: the prompt naming the task is there when you start one and gone when
        // you come back to it. Until that is decided this waits for what the container actually
        // shows rather than for what it ought to.
        terminal.await("agent@", Terminal.BUILD_PATIENCE);
    }

    @When("I wait for the shell inside the container")
    public void iWaitForTheShellInside() throws IOException {
        // The task's own prompt, which is how a scenario knows it is inside rather than still
        // watching the start report scroll past. The long patience is here and nowhere else:
        // this is the step behind which an image gets built, and only the first one pays it.
        terminal.await("sokar[", Terminal.BUILD_PATIENCE);
    }

    @Then("the terminal shows {string}")
    public void theTerminalShows(String text) throws IOException {
        terminal.await(text);
        assertThat(terminal.seen()).contains(text);
    }

    @Then("the terminal does not show {string}")
    public void theTerminalDoesNotShow(String text) throws IOException {
        terminal.drain();
        assertThat(terminal.seen()).doesNotContain(text);
    }

    @Given("a project called {string} with a file in it")
    public void aProject(String name) throws IOException {
        // Built by running the commands rather than by writing files from here: a fixture the
        // suite creates is a fixture that can be right while the product is wrong.
        machine().run("rm -rf ~/" + name + " && mkdir -p ~/" + name);
        machine().run("cd ~/" + name + " && git init -q -b main . "
                + "&& git config user.email t@example.com && git config user.name T "
                + "&& echo 'the project' > README.md && git add -A && git commit -q -m initial");
        machine().run("cd ~/" + name + " && printf '%s\\n' "
                + "'project:' '  name: \"" + name + "\"' '  security_class: \"offline\"' "
                + "'image:' '  base_image: \"ubuntu:24.04\"' > project.yml");
    }

    @When("the machine restarts")
    public void theMachineRestarts() throws IOException {
        if (terminal != null) {
            terminal.close();
            terminal = null;
        }
        machine().restart().restart();
    }

    @When("a script runs {string}")
    public void aScriptRuns(String command) throws IOException {
        output = machine().run(command);
    }

    @Then("a script running {string} mentions {string}")
    public void aScriptRunningMentions(String command, String text) throws IOException {
        output = machine().run(command);
        assertThat(output.all()).as("running: %s", command).contains(text);
    }

    @Then("it exits non-zero")
    public void itExitsNonZero() {
        assertThat(output.status()).isNotZero();
    }

    @Then("it exits zero")
    public void itExitsZero() {
        assertThat(output.status()).isZero();
    }

    @Then("its output contains {string}")
    public void itsOutputContains(String text) {
        assertThat(output.all()).contains(text);
    }

    @Then("its output does not contain {string}")
    public void itsOutputDoesNotContain(String text) {
        assertThat(output.all()).doesNotContain(text);
    }

    @Then("its output mentions one of {string}")
    public void itsOutputMentionsOneOf(String alternatives) {
        // For a fact with several honest shapes. Asserting one of them would make the scenario
        // depend on which machine it ran on, which is how a suite teaches people to re-run it.
        final java.util.List<String> any = java.util.Arrays.stream(alternatives.split(","))
                .map(String::strip).toList();
        assertThat(any).as("output was:%n%s", output.all())
                .anyMatch(text -> output.all().contains(text));
    }

    @Then("its output contains no escape sequences")
    public void itsOutputHasNoEscapes() {
        // The other half of every colour scenario. A suite that only ever allocates a pty proves
        // that colour appears and never that it stays out of a pipe, a log or a fixture.
        assertThat(output.all()).doesNotContain("\033[");
    }
}
