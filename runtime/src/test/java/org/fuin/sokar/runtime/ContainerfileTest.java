package org.fuin.sokar.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.junit.jupiter.api.Test;

/**
 * Golden-file test for {@link Containerfile}.
 * <p>
 * The fixture is compared byte for byte. When Containerfile generation moves to a template engine,
 * this test is what decides whether the generated image is still the same image.
 */
class ContainerfileTest {

    @Test
    void rendersTheExpectedFile() throws IOException {

        final Project project = new Project("uc", "Ultimate Container", SecurityClass.GUARDED, "ubuntu:24.04");

        assertThat(Containerfile.render(project)).isEqualTo(golden("Containerfile.uc"));
    }

    @Test
    void carriesTheSecurityClassAsALabel() {

        final Project project = new Project("uc", "", SecurityClass.OFFLINE, "ubuntu:24.04");

        assertThat(Containerfile.render(project)).contains("org.fuin.sokar.security-class=\"offline\"");
    }

    private String golden(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/golden/" + name)) {
            assertThat(in).as("fixture /golden/" + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
