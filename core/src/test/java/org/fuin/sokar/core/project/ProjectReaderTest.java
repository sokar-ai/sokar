package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProjectReader}.
 */
class ProjectReaderTest {

    private static final String MINIMAL = """
            project:
              name: "uc"
              description: "Ultimate Container"
              security_class: "online"

            image:
              base_image: "ubuntu:24.04"
            """;

    private static Project read(String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test.yml");
    }

    @Test
    void readsAProjectDefinition() {

        final Project project = read(MINIMAL);

        assertThat(project.name()).isEqualTo("uc");
        assertThat(project.description()).isEqualTo("Ultimate Container");
        assertThat(project.securityClass()).isEqualTo(SecurityClass.ONLINE);
        assertThat(project.baseImage()).isEqualTo("ubuntu:24.04");
        assertThat(project.imageName()).isEqualTo("sokar/uc");
    }

    @Test
    void ignoresSectionsItDoesNotUnderstandYet() {

        // A project file written for a later version must not stop an older binary from running.
        final Project project = read(MINIMAL + """
                git:
                  upstream_url: "https://example.com/x.git"

                run:
                  gpus: all
                """);

        assertThat(project.name()).isEqualTo("uc");
    }

    @Test
    void descriptionIsOptional() {

        final Project project = read("""
                project:
                  name: "uc"
                  security_class: "offline"
                image:
                  base_image: "ubuntu:24.04"
                """);

        assertThat(project.description()).isEmpty();
    }

    @Test
    void rejectsAMissingSection() {

        assertThatThrownBy(() -> read("project:\n  name: uc\n  security_class: offline\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("no 'image' section");
    }

    @Test
    void rejectsAMissingKey() {

        assertThatThrownBy(() -> read("""
                project:
                  name: "uc"
                image:
                  base_image: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("project.security_class");
    }

    @Test
    void rejectsAnUnknownSecurityClass() {

        assertThatThrownBy(() -> read(MINIMAL.replace("online", "yolo")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("offline, guarded, online");
    }

    @Test
    void rejectsANameThatWillNotSurviveTheLayersBelow() {

        assertThatThrownBy(() -> read(MINIMAL.replace("\"uc\"", "\"Ultimate Container\"")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Invalid project name");
    }

    @Test
    void rejectsDuplicateKeys() {

        // A duplicate silently wins in most YAML readers. In a security policy that is a bug.
        assertThatThrownBy(() -> read(MINIMAL.replace("image:\n  base_image",
                "image:\n  base_image: \"a\"\n  base_image")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Cannot parse");
    }

    @Test
    void reportsTheOriginOfTheProblem() {

        assertThatThrownBy(() -> ProjectReader.read(new StringReader("[]"), "/etc/sokar/project.yml"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("/etc/sokar/project.yml");
    }
}
