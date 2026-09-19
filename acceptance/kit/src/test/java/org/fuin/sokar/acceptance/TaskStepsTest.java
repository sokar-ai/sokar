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
    void namesTheProjectAndStandsNowhere() {
        // A project is named, not pointed at: which directory the step runs in means nothing, and
        // a scenario that cd'd somewhere would be describing a rule that no longer exists.
        assertThat(TaskSteps.startCommand("nocred", "omp")).startsWith("sokar task start ");
        assertThat(TaskSteps.startCommand("nocred", "omp")).doesNotContain("cd ");
        assertThat(TaskSteps.startCommand("nocred", "omp")).contains("--project 'nocred'");
    }

    @Test
    void startsSomethingNobodyIsWatching() {
        // A prompt is what makes a run unattended, and only an unattended run is refused for a
        // missing credential rather than warned about.
        assertThat(TaskSteps.startCommand("nocred", "omp"))
                .isEqualTo("sokar task start --project 'nocred' --agent 'omp'"
                        + " --repository 'nocred' --prompt 'hello'");
    }

    @Test
    void asksWhatWouldHappenWithoutDoingIt() {
        assertThat(TaskSteps.planCommand("noask", "claude"))
                .isEqualTo("sokar task start --project 'noask' --agent 'claude'"
                        + " --repository 'noask' --dry-run --detach");
    }

    @Test
    void quotesTheAgentBecauseItIsNotExpanded() {
        assertThat(TaskSteps.startCommand("p", "a b")).contains("--agent 'a b'");
    }

    @Test
    void refusesSomethingNoProjectCouldBeCalled() {
        // The name is quoted where it goes now, so this is not what stands between a fixture and a
        // shell. It stops a scenario naming something no project could be called, which would
        // otherwise fail deep inside a start with a message about a project nobody has.
        assertThatThrownBy(() -> TaskSteps.startCommand("p; rm -rf ~", "omp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a project name");
        assertThatThrownBy(() -> TaskSteps.planCommand("$(whoami)", "omp"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
