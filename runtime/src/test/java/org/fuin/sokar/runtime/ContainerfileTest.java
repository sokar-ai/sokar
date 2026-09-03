package org.fuin.sokar.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
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

        final Project project = new Project("uc", "Ultimate Container", SecurityClass.GUARDED, "ubuntu:24.04", null);

        assertThat(Containerfile.render(project)).isEqualTo(golden("Containerfile.uc"));
    }

    @Test
    void putsContributedLayersOnTheRightSideOfTheUserSwitch() {

        // An agent's download must run as the agent, or the binary lands in root's home and the
        // agent cannot execute it. Root fragments must run before the switch, or they cannot
        // install packages. Getting this backwards produces an image that builds and then fails
        // at run time.
        final Project project = new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null);
        final String rendered = Containerfile.render(project,
                new ImageLayers(List.of("RUN apt-get install -y jq"),
                        List.of("RUN curl -o /home/agent/.local/bin/x https://example.com/x")));

        final int root = rendered.indexOf("apt-get install -y jq");
        final int switchUser = rendered.indexOf("USER agent");
        final int agent = rendered.indexOf("/home/agent/.local/bin/x");

        assertThat(root).isGreaterThan(-1).isLessThan(switchUser);
        assertThat(agent).isGreaterThan(switchUser);
    }

    @Test
    void theProjectsOwnLinesGoIntoTheImage() {

        // This is how additional tooling gets into a box.
        final Project project = new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04",
                "RUN apt-get update && apt-get install -y ripgrep\n");

        assertThat(project.imageSnippetLines()).containsExactly(
                "RUN apt-get update && apt-get install -y ripgrep");
    }

    @Test
    void everyImageCanFetchAndVerifyADownload() {

        // Every pinned agent install needs curl, and a base image cannot be assumed to have it.
        // Without this the failure is a confusing "curl: not found" inside someone else's layer.
        assertThat(Containerfile.render(
                new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null)))
                .contains("command -v curl")
                .contains("ca-certificates");
    }

    @Test
    void saysWhichBaseImageCannotBePreparedRatherThanFailingObscurely() {

        assertThat(Containerfile.render(
                new Project("uc", "", SecurityClass.GUARDED, "scratch", null)))
                .contains("no curl and no known package manager in scratch");
    }

    @Test
    void carriesTheSecurityClassAsALabel() {

        final Project project = new Project("uc", "", SecurityClass.OFFLINE, "ubuntu:24.04", null);

        assertThat(Containerfile.render(project)).contains("org.fuin.sokar.security-class=\"offline\"");
    }

    private String golden(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/golden/" + name)) {
            assertThat(in).as("fixture /golden/" + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
