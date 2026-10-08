package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.sokar.runtime.ContainerSummary;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TaskLaunch#ofAnotherProject}: a container's name is not enough to say whose task it is.
 */
class ProjectOfContainerTest {

    @Test
    void aContainerOfAnotherProjectWithTheSameNameIsNotThisTask() {

        // 'web' with task 'app-x' and 'web-app' with task 'x' are both sokar-web-app-x: a start in 'web' brought back
        // the other project's task, with its workspace and its egress.
        final ContainerSummary theirs = new ContainerSummary("sokar-web-app-x", "Exited (0)", "", "web-app", "guarded",
                "web-app", null);

        assertThat(TaskLaunch.ofAnotherProject(theirs, "web")).contains("web-app").contains("sokar-web-app-x");
        assertThat(TaskLaunch.ofAnotherProject(theirs, "web-app")).isNull();
        // A container from before the label existed is taken by its name, as before.
        assertThat(TaskLaunch.ofAnotherProject(new ContainerSummary("sokar-web-app-x", "Exited (0)", "", null, null,
                null, null), "web")).isNull();
    }
}
