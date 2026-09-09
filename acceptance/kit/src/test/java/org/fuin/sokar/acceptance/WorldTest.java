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
}
