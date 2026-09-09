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

    @Given("a terminal on the machine")
    public void aTerminal() throws IOException {
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

    @When("a script runs {string}")
    public void aScriptRuns(String command) throws IOException {
        output = machine().run(command);
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

    @Then("its output contains no escape sequences")
    public void itsOutputHasNoEscapes() {
        // The other half of every colour scenario. A suite that only ever allocates a pty proves
        // that colour appears and never that it stays out of a pipe, a log or a fixture.
        assertThat(output.all()).doesNotContain("\033[");
    }
}
