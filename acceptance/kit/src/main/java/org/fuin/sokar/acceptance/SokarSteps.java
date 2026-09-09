package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Sokar-shaped setup a scenario needs before the thing it is about: an unlocked vault, a
 * credential in it, and a task put away afterwards.
 * <p>
 * Here rather than in each agent's repository, so that a suite of agent scenarios needs no glue of
 * its own. Each step runs the real command; nothing writes a file Sokar would have written.
 */
public class SokarSteps {

    /** The start report names the container on a line of its own. */
    private static final Pattern CONTAINER = Pattern.compile("(?m)^container\\s+(\\S+)");

    private final World world;

    /**
     * Constructor with the scenario's state.
     *
     * @param world What the scenario holds.
     */
    public SokarSteps(World world) {
        this.world = world;
    }

    /**
     * Unlocks the vault - creating it on a machine that has none - without a prompt.
     * <p>
     * The interactive unlock, with the passphrase typed and not echoed, is a scenario of its own;
     * this is the setup for every scenario that merely needs the vault open.
     *
     * @param passphrase The passphrase, without whitespace.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("the vault is unlocked with the passphrase {string}")
    public void theVaultIsUnlocked(String passphrase) throws IOException {
        // The command is split by Sokar, so a passphrase with a space would arrive as two words.
        assertThat(passphrase).doesNotContainAnyWhitespaces();
        final Machine.Output output = world.machine().run(
                "sokar vault unlock --passphrase-command " + Shell.quote("printf " + passphrase));
        assertThat(output.status()).as("vault unlock said:%n%s", output.all()).isZero();
    }

    /**
     * Stores a credential from the runner's environment, through standard input.
     *
     * @param variable The variable holding the value.
     * @param name What the vault stores it under - the provider's name.
     * @param kind Its kind, for example {@code api-key}.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("the vault holds the value of {string} as {string} of kind {string}")
    public void theVaultHolds(String variable, String name, String kind) throws IOException {
        final Machine.Output output = world.machine().run(
                "sokar vault put " + name + " --type " + kind, world.secret(variable));
        assertThat(output.status()).as("vault put said:%n%s", output.all()).isZero();
        assertThat(world.contains(variable, output.all()))
                .as("vault put printed the value of %s", variable).isFalse();
    }

    /**
     * Stops and removes the task the terminal's start report named.
     * <p>
     * A rented machine is destroyed with its tasks; a developer's VM is not, and a suite that
     * leaves a container behind every run is one that stops being run.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @Then("the task that was started is stopped and purged")
    public void theTaskIsStoppedAndPurged() throws IOException {
        final Matcher matcher = CONTAINER.matcher(world.terminal().seen());
        assertThat(matcher.find()).as("no start report named a container in:%n%s",
                world.terminal().seen()).isTrue();
        final Machine.Output output = world.machine().run(
                "sokar task stop " + matcher.group(1) + " --purge");
        assertThat(output.status()).as("task stop said:%n%s", output.all()).isZero();
    }
}
