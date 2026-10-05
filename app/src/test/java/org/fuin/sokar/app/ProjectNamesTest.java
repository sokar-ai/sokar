package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProjectNames#of}: whose task a container is, when names share a beginning.
 */
class ProjectNamesTest {

    @Test
    void theLabelSaysWhoseTaskItIsAndANameAloneOnlyWhenItCanMeanOneProject() {

        // Taken by its name's beginning, a task of 'web-app' was one of 'web' in messaging - web's peers, web's
        // conversation, web's tasks as vouched siblings - and the release check made the opposite mistake.
        final List<String> projects = List.of("web", "web-app");

        assertThat(ProjectNames.of("sokar-web-app-x", Map.of("sokar-web-app-x", "web-app"), projects)).isEqualTo("web-app");
        assertThat(ProjectNames.of("sokar-web-app-x", Map.of("sokar-web-app-x", "web"), projects)).isEqualTo("web");
        assertThat(ProjectNames.of("sokar-web-app-x", Map.of(), projects)).as("two could be meant").isNull();
        assertThat(ProjectNames.of("sokar-web-x", Map.of(), List.of("web"))).isEqualTo("web");
    }
}
