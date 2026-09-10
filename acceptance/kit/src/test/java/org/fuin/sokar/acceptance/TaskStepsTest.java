package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for the commands {@link TaskSteps} builds.
 * <p>
 * These run nowhere near a machine, which is the point: the mistakes worth catching here are
 * quoting mistakes, and those otherwise surface minutes into a run on a rented server.
 */
class TaskStepsTest {

    @Test
    void leavesTheHomeShortcutWhereTheShellCanSeeIt() {
        // Quoting it would send 'cd ~/nocred' as a literal path and fail with "no such file or
        // directory" naming a tilde.
        assertThat(TaskSteps.startCommand("nocred", "omp")).startsWith("cd ~/nocred &&");
        assertThat(TaskSteps.startCommand("nocred", "omp")).doesNotContain("'~/");
        assertThat(TaskSteps.startCommand("nocred", "omp")).doesNotContain("$HOME");
    }

    @Test
    void startsSomethingNobodyIsWatching() {
        // A prompt is what makes a run unattended, and only an unattended run is refused for a
        // missing credential rather than warned about.
        assertThat(TaskSteps.startCommand("nocred", "omp"))
                .isEqualTo("cd ~/nocred && sokar task run --agent 'omp' --prompt 'hello'");
    }

    @Test
    void asksWhatWouldHappenWithoutDoingIt() {
        assertThat(TaskSteps.planCommand("noask", "claude"))
                .isEqualTo("cd ~/noask && sokar task run --agent 'claude' --dry-run --no-attach");
    }

    @Test
    void quotesTheAgentBecauseItIsNotExpanded() {
        assertThat(TaskSteps.startCommand("p", "a b")).contains("--agent 'a b'");
    }

    @Test
    void refusesAProjectNameItWouldHaveToLeaveUnquoted() {
        // The name goes into a shell command bare so the tilde expands, so anything that is not a
        // name is refused here rather than becoming shell.
        assertThatThrownBy(() -> TaskSteps.startCommand("p; rm -rf ~", "omp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a project name");
        assertThatThrownBy(() -> TaskSteps.planCommand("$(whoami)", "omp"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
