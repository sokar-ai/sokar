package org.fuin.sokar.acceptance;

import org.fuin.sokar.machines.Ssh;
import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import java.io.IOException;

/**
 * The Sokar-shaped setup a scenario needs before the thing it is about: an unlocked vault and a
 * credential in it.
 * <p>
 * Here rather than in each agent's repository, so that a suite of agent scenarios needs no glue of
 * its own. Each step runs the real command; nothing writes a file Sokar would have written.
 * <p>
 * Tearing a task down is deliberately <em>not</em> here. It was, as one step that knew the verbs,
 * and that is what made it wrong: when the lifecycle verbs changed, the step went on building a
 * command the CLI refuses, and every repository using the kit went red at once from a line none of
 * them could see. Removing a task is a script a scenario runs, in the words the scenario chose.
 */
public class SokarSteps {

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
        // Typed at the prompt, which is what an operator does and what this kit exists to
        // simulate. It also keeps the passphrase off every command line and out of every file:
        // '--passphrase-command "printf <value>"' - the obvious form - puts it in the argv of
        // sokar AND of the command it spawns, and /proc/<pid>/cmdline is world-readable with
        // /proc mounted without hidepid on both supported distributions.
        //
        // A feature file's passphrase is public by construction, so nothing here is at risk. The
        // reason to do it properly is that this is a published kit: the obvious step is the one
        // somebody reaches for with a real passphrase.
        try (Terminal terminal = world.machine().terminal()) {
            terminal.type("sokar vault unlock");
            terminal.await("passphrase");
            terminal.type(passphrase);
            // 'await' matches literal text, not a pattern - so the SHORTEST text that only a
            // successful unlock prints. A refusal ends as a timeout carrying the whole screen,
            // which says more than a matched prefix would.
            //
            // Short on purpose, and this is the lesson rather than a detail. This waited for
            // "cached in the kernel keyring"; the sentence was reworded to say WHERE the
            // passphrase goes - "cached in this account's kernel keyring..." - and every agent
            // repository's leg went red on an unlock that had worked, because a published kit
            // runs against whatever sokar a machine has. A kit must not hold a sentence we may
            // improve: it waits for the part that carries the meaning and would have to change
            // for the wrong reason.
            terminal.await("cached");
            // The whole point of the prompt: it must not appear on the screen it was typed at.
            assertThat(terminal.seen()).as("the passphrase was echoed").doesNotContain(passphrase);
        }
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
        final Ssh.Output output = world.machine().run(
                "sokar vault put " + name + " --type " + kind, world.secret(variable));
        assertThat(output.status()).as("vault put said:%n%s", output.all()).isZero();
        assertThat(world.contains(variable, output.all()))
                .as("vault put printed the value of %s", variable).isFalse();
    }

}
