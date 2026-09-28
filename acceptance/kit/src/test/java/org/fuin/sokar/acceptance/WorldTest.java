package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link World}.
 */
class WorldTest {

    @Test
    void expandsAPlaceholderFromTheEnvironment() {
        // PATH is set on every machine that can run this test; the value is not the point.
        assertThat(World.expand("echo ${PATH}")).isEqualTo("echo " + System.getenv("PATH"));
    }

    @Test
    void putsTheTaskWhereTheCommandSaysQuoted() {
        assertThat(World.aboutTask("sokar talk held {task}", task("sokar-p-shell-1790000000000", null)))
                .isEqualTo("sokar talk held 'sokar-p-shell-1790000000000'");
    }

    @Test
    void putsTheStateDirectoryWhereTheCommandSaysQuoted() {
        assertThat(World.aboutTask("cat {state}/hooks.log", task("sokar-p-shell-1", "/run/user/1/sokar/sokar-p-shell-1")))
                .isEqualTo("cat '/run/user/1/sokar/sokar-p-shell-1'/hooks.log");
    }

    @Test
    void refusesACommandAboutTheTaskThatDoesNotSayWhere() {
        // Run as written, it would act on no task and fail in a way that reads as the product's.
        assertThatThrownBy(() -> World.aboutTask("sokar talk held", task("sokar-p-shell-1", null)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("does not say where");
    }

    @Test
    void refusesTheStateDirectoryOfATaskWhoseStartNamedNone() {
        // Replaced by nothing, the command would read the root of the machine rather than the task's files.
        assertThatThrownBy(() -> World.aboutTask("ls {state}", task("sokar-p-shell-1", null)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("did not say where");
    }

    private static World.Task task(String container, @org.jspecify.annotations.Nullable String state) {
        return new World.Task(container, state, "");
    }

    @Test
    void leavesACommandWithoutPlaceholdersAlone() {
        assertThat(World.expand("sokar task list")).isEqualTo("sokar task list");
    }

    @Test
    void refusesAPlaceholderNobodySet() {
        // Silently typing '${SOKAR_E2E_MODEL}' into a terminal would fail on the machine, in a
        // way that reads as the product not knowing the model.
        assertThatThrownBy(() -> World.expand("x ${SOKAR_ACCEPTANCE_NOT_A_VARIABLE_1} y"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SOKAR_ACCEPTANCE_NOT_A_VARIABLE_1");
    }

    @Test
    void doesNotExpandAShellVariable() {
        // '$HOME' is the machine's business, not the runner's: only the braced form is ours.
        assertThat(World.expand("ls $HOME")).isEqualTo("ls $HOME");
    }

    @Test
    void saysWhichVariableASecretCameFromAndNeverItsValue() {
        assertThatThrownBy(() -> new World().secret("SOKAR_ACCEPTANCE_NOT_A_VARIABLE_2"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SOKAR_ACCEPTANCE_NOT_A_VARIABLE_2");
    }

    @Test
    void redactsASecretFromWhatACommandPrinted() {
        final World world = new World();
        world.remember("KEY", "sk-live-secret");

        assertThat(world.redact("refused: sk-live-secret is not valid"))
                .isEqualTo("refused: <value of KEY> is not valid");
    }

    @Test
    void findsASecretWithAWindowsLineBreakInIt() {
        final World world = new World();
        world.remember("KEY", "sk-live-secret");

        assertThat(world.containsAcrossLines("KEY", "sk-live-\r\nsecret")).isTrue();
        assertThat(world.contains("KEY", "sk-live-\r\nsecret")).isFalse();
    }

    @Test
    void sendsEveryCommandWithTheScenariosOwnVault() {
        final World world = new World();
        world.vault("/tmp/tmp.a b/vault.bin");

        assertThat(world.environment()).isEqualTo("export SOKAR_VAULT='/tmp/tmp.a b/vault.bin'; ");
    }

    @Test
    void addsNothingWhenTheScenarioUsesTheAccountsVault() {
        assertThat(new World().environment()).isEmpty();
    }
}
