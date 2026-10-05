package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * A setting Sokar no longer reads is refused by name, wherever it stands: a file that carries it would otherwise
 * look as if it still did something.
 */
class ProjectRemovedSettingTest {

    private static Project read(final String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test");
    }

    @Test
    void refusesUnreadWorkMayLeaveUnderProjectAndSaysWhatDecidesInstead() {
        assertThatThrownBy(() -> read("""
                project:
                  name: "p"
                  security_class: "guarded"
                  unread_work_may_leave: true
                image:
                  base_image: "ubuntu:24.04"
                """)).isInstanceOf(ProjectException.class)
                .as("the key named, and what replaces it")
                .hasMessageContaining("'project.unread_work_may_leave' is no longer a setting")
                .hasMessageContaining("mail.rules");
    }

    @Test
    void refusesItInAnyOtherPlaceByTheSameName() {
        assertThatThrownBy(() -> read("""
                project:
                  name: "p"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                mail:
                  unread_work_may_leave: false
                """)).isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mail.unread_work_may_leave' is no longer a setting");
    }
}
