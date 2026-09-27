package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for what {@link TerminalSteps} checks before it touches a machine.
 */
class TerminalStepsTest {

    @Test
    void refusesAProjectNameThatWouldDeleteTheHomeItLivesIn() {
        assertThatThrownBy(() -> TerminalSteps.projectName("..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TerminalSteps.projectName(".")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TerminalSteps.projectName("a/../..")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAProjectNameWithADotInIt() {
        assertThat(TerminalSteps.projectName("live.1")).isEqualTo("live.1");
    }

    @Test
    void remembersAProjectForTheWholeRunNotOneScenario() {
        World.takeProjects();
        new World().made("restarted");
        new World().made("restarted");

        assertThat(World.takeProjects()).as("a second scenario sharing the project names it once").containsExactly("restarted");
        assertThat(World.takeProjects()).isEmpty();
    }

    @Test
    void refusesADirectoryNoAcceptanceRunMadeAndSaysWhy() {
        assertThat(TerminalSteps.fixtureCheck("live"))
                .isEqualTo("if [ -e ~/'live' ] && [ ! -e ~/'live'/.git/sokar-acceptance-fixture ]; then echo 'refused: live"
                        + " is in the home and no acceptance run made it - a project of a person is not a fixture';"
                        + " exit 1; fi");
    }

}
