package org.fuin.sokar.acceptance;

import org.fuin.sokar.machines.Ssh;
import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
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
     * Gives the scenario a vault of its own, in a new temporary directory, and unlocks it.
     * <p>
     * <strong>The account's vault is never read or replaced.</strong> A scenario that used it depended
     * on what an earlier run left there: on 2026-09-27 the test account's vault had a passphrase
     * nobody recorded, and every scenario that needed a credential stopped at the unlock. With a vault
     * of its own, the passphrase is the one the scenario states, a machine with no vault works the
     * same, and nothing an operator keeps is at stake. Every command the scenario sends afterwards
     * carries {@code SOKAR_VAULT}, so no step can act on the account's vault by going around it.
     *
     * @param passphrase The passphrase the new vault is made with, without whitespace.
     * @throws IOException If the machine cannot be reached.
     */
    @Given("a vault of this scenario's own, unlocked with the passphrase {string}")
    public void aVaultOfItsOwn(String passphrase) throws IOException {
        final Ssh.Output made = world.machine().run("mktemp -d");
        assertThat(made.status()).as("could not make a directory for the scenario's vault:%n%s", made.all()).isZero();
        world.vault(made.out().strip() + "/vault.bin");
        // Made by 'vault init', typed twice at the prompt as a person does: an unlock only caches a
        // passphrase, and a scenario that believed it had a vault had none - 'vault devices' found
        // nothing, and an unlock with a wrong passphrase was accepted, there being nothing to open.
        try (Terminal terminal = world.openTerminal()) {
            terminal.type("sokar vault init");
            terminal.await("passphrase");
            terminal.type(passphrase);
            terminal.await("again");
            terminal.type(passphrase);
            terminal.await("created");
            terminal.await("cached");
            assertThat(terminal.seen()).as("the passphrase was echoed").doesNotContain(passphrase);
        }
        final Ssh.Output exists = world.run("test -f \"$SOKAR_VAULT\"");
        assertThat(exists.status()).as("'vault init' made no vault at %s", world.vault()).isZero();
    }

    /**
     * Removes a scenario's own vault, whether the scenario passed or not.
     * <p>
     * Here although tearing down is not, because this removes only what the kit itself made. The
     * {@code lock} that forgets the cached passphrase is best effort: a renamed verb must not turn
     * every scenario red, and a passphrase cached for a file that no longer exists opens nothing.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @After
    public void removeTheScenariosVault() throws IOException {
        final String vault = world.vault();
        if (vault == null) {
            return;
        }
        world.run("sokar vault lock");
        world.machine().run("rm -rf " + Shell.quote(vault.substring(0, vault.lastIndexOf('/'))));
        world.vault(null);
    }

    /**
     * Unlocks the vault the machine already has. With none, the unlock refuses and names
     * {@code vault init}; a scenario that needs a vault makes one with "a vault of this scenario's own".
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
        try (Terminal terminal = world.openTerminal()) {
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
        // "cached" only says the command finished: the refusal says "nothing was cached", and this step
        // passed on a wrong passphrase until 2026-09-27. Whether the vault opens is asked of the machine.
        final Ssh.Output open = world.run("sokar vault list");
        assertThat(open.status()).as("the vault did not open with that passphrase:%n%s", open.all()).isZero();
    }

    /**
     * Gives the scenario a daemon of its own, reading the scenario's vault.
     * <p>
     * The account's daemon reads the account's vault, so a question about this scenario's credential
     * asked of it would be answered about somebody else's. The socket's path is fixed, so the account's
     * daemon is stopped for the scenario and started again after it - inside this account only, and
     * only if it was running.
     * <p>
     * <strong>A systemd unit, as the account's daemon is</strong>, restarted on failure the same way: what
     * a scenario proves about stopping or losing a daemon has to be proved of one whose unit's control
     * group is what systemd stops.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @Given("a daemon of this scenario's own")
    public void aDaemonOfItsOwn() throws IOException {
        final Ssh.Output stopped = world.run(DAEMON_STOP);
        assertThat(stopped.status()).as("could not stop the account's daemon:%n%s", stopped.all()).isZero();
        world.daemon(stopped.out().contains(WAS_RUNNING), UNIT);
        theDaemonIsStartedAgain();
    }

    /**
     * Starts the scenario's daemon's unit, and waits until the daemon answers.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @When("the daemon is started again")
    public void theDaemonIsStartedAgain() throws IOException {
        final Ssh.Output started = world.run(DAEMON_START);
        assertThat(started.status()).as("the scenario's daemon did not answer:%n%s", started.all()).isZero();
    }

    /**
     * Stops the scenario's daemon the way an operator stops one: through its unit.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @When("the daemon's unit is stopped")
    public void theDaemonsUnitIsStopped() throws IOException {
        final Ssh.Output stopped = world.run("systemctl --user stop " + UNIT + " && ! " + DAEMON_ASK);
        assertThat(stopped.status()).as("the daemon still answers after its unit was stopped:%n%s", stopped.all())
                .isZero();
    }

    /**
     * Kills the scenario's daemon as a crash would, and waits for systemd to bring it back.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @When("the daemon crashes and systemd restarts it")
    public void theDaemonCrashes() throws IOException {
        final Ssh.Output crashed = world.run("before=$(systemctl --user show -p MainPID --value " + UNIT + ");"
                + " systemctl --user kill -s KILL " + UNIT + ";"
                + " for i in $(seq 100); do now=$(systemctl --user show -p MainPID --value " + UNIT + ");"
                + " if [ \"$now\" != 0 ] && [ \"$now\" != \"$before\" ] && " + DAEMON_ASK + "; then exit 0; fi;"
                + " sleep 0.2; done; systemctl --user status " + UNIT + " --no-pager; exit 1");
        assertThat(crashed.status()).as("systemd did not bring the daemon back:%n%s", crashed.all()).isZero();
    }

    /** The scenario's daemon's unit. */
    static final String UNIT = "sokar-acceptance-sokard";

    /** Printed by {@link #DAEMON_STOP} when there was a daemon of the account's to start again. */
    static final String WAS_RUNNING = "the daemon of this account was running";

    /** Stops the account's daemon, if systemd runs one, and says whether it did. */
    static final String DAEMON_STOP = "if systemctl --user is-active -q sokard 2>/dev/null; then"
            + " systemctl --user stop sokard && echo '" + WAS_RUNNING + "'; fi";

    /** What asks a daemon whether it is there: the socket file outlives the process that made it. */
    static final String DAEMON_ASK = "printf '{\"method\":\"org.varlink.service.GetInfo\",\"parameters\":{}}\\0'"
            + " | timeout 5 sokar daemon connect 2>/dev/null | grep -q vendor";

    /**
     * Starts the daemon as a transient unit with this scenario's vault and PATH, and waits until it answers.
     * The unit's manager has a PATH of its own, so the daemon is named by the path this session finds.
     */
    static final String DAEMON_START = "if " + DAEMON_ASK + "; then echo 'another daemon answers on the socket'; exit 1; fi;"
            + " systemd-run --user --quiet --collect --unit=" + UNIT + " -p Restart=on-failure -p RestartSec=1"
            + " --setenv=PATH=\"$PATH\" ${SOKAR_VAULT:+--setenv=SOKAR_VAULT=\"$SOKAR_VAULT\"} \"$(command -v sokard)\""
            + " || exit 1; for i in $(seq 50); do " + DAEMON_ASK + " && exit 0; sleep 0.2; done;"
            + " journalctl --user -u " + UNIT + " -n 20 --no-pager; exit 1";

    /**
     * Stops the scenario's daemon and starts the account's again, whether the scenario passed or not.
     *
     * @throws IOException If the machine cannot be reached.
     */
    @After
    public void stopTheScenariosDaemon() throws IOException {
        if (world.daemonUnit() != null) {
            world.machine().run("systemctl --user stop " + UNIT + " 2>/dev/null; systemctl --user reset-failed "
                    + UNIT + " 2>/dev/null; true");
        }
        if (world.daemonWasRunning()) {
            world.machine().run("systemctl --user start sokard");
        }
        world.daemon(false, null);
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
        final Ssh.Output output = world.run(
                "sokar vault put " + name + " --type " + kind, world.secret(variable));
        assertThat(output.status()).as("vault put said:%n%s", output.all()).isZero();
        assertThat(world.contains(variable, output.all()))
                .as("vault put printed the value of %s", variable).isFalse();
    }

}
