package org.fuin.sokar.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.fuin.sokar.core.project.Limits;
import org.fuin.sokar.core.project.Egress;
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
    void writesTheSourcesAProjectNames() {

        // The point of the key: a machine behind a corporate mirror, or one where Canonical's own
        // is the only thing reachable, says so once in the project file.
        final Project project = new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null,
                null, Limits.defaults(), Egress.none(),
                List.of("http://mirror.example.test/ubuntu/"));

        // The URIs line itself, not the whole file: the comment above it names the default's
        // hosts as measurements, and a naive doesNotContain trips over its own documentation.
        assertThat(Containerfile.render(project))
                .contains("URIs: http://mirror.example.test/ubuntu/|")
                .doesNotContain("URIs: http://azure.archive.ubuntu.com");
    }

    @Test
    void refusesNamedSourcesOnABaseWithNoApt() {

        // Said rather than ignored. Somebody who names sources under an image that cannot use them
        // has an expectation nothing will meet, and a silent no-op reads as configured.
        final Project named = new Project("uc", "", SecurityClass.GUARDED, "fedora:41", null,
                null, Limits.defaults(), Egress.none(), List.of("http://mirror.example.test/f/"));
        assertThat(Containerfile.render(named)).contains("elif true; then");

        // And a project that named nothing must still build on the same base.
        final Project silent = new Project("uc", "", SecurityClass.GUARDED, "fedora:41", null);
        assertThat(Containerfile.render(silent)).contains("elif false; then");
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

        // Every pinned agent install needs curl, cloning the workspace needs git, and an online
        // project pushes over ssh; a base image cannot be assumed to have any of them. Without
        // this the failure is a confusing "not found" inside someone else's layer.
        assertThat(Containerfile.render(
                new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null)))
                .contains("command -v curl")
                .contains("command -v git")
                .contains("command -v ssh")
                .contains("ca-certificates");
    }

    @Test
    void saysWhichBaseImageCannotBePreparedRatherThanFailingObscurely() {

        assertThat(Containerfile.render(
                new Project("uc", "", SecurityClass.GUARDED, "scratch", null)))
                .contains("no curl/git/ssh/tmux and no known package manager in scratch");
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

    @Test
    void pinsHowMuchASessionRemembers() {

        // The figure 'sokar task attach' claims on returning. Inherited rather than pinned, it
        // would be whatever the base image or a dotfile happened to say, and an interface would
        // have nothing true to put on the screen.
        final Project project = new Project("uc", "Ultimate Container", SecurityClass.GUARDED,
                "ubuntu:24.04", null);

        assertThat(Containerfile.render(project))
                .contains("history-limit " + Containerfile.SCROLLBACK)
                .contains("/etc/sokar/tmux.conf")
                .contains("set -gq extended-keys on");
    }

    @Test
    void everyImageCarriesAUtf8Locale() {

        // Measured on the Ubuntu VM, 2026-09-12, and reported by the operator before that: the
        // base image sets no LANG and 'podman exec' passes none, so LC_CTYPE was POSIX and an
        // agent's terminal interface arrived with every non-ASCII character replaced - its
        // banner, its prompt markers and its spinner all underscores. In the same container the
        // shell echoed typed characters as octal escapes.
        //
        // LANG, not LC_ALL: LANG is what every LC_* category falls back to, so this sets a
        // default without taking away the ability to override one category.
        final Project project = new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null);

        assertThat(Containerfile.render(project))
                .contains("ENV LANG=C.UTF-8")
                .doesNotContain("ENV LC_ALL");
    }

    @Test
    void changingWhatSokarPutsInEveryImageMakesExistingOnesStale() {

        // The defect this pins: the fingerprint covered the project's answers and not Sokar's own
        // half of the recipe, so a release that changed what every image contains produced no
        // drift anywhere. Adding a UTF-8 locale fixed nothing on a machine that already had an
        // image built - the label said the recipe was unchanged, and it was not.
        //
        // Asserted through a property rather than a literal digest: a test that named the hash
        // would have to be edited by whoever broke it, which is the one person it has to survive.
        final Project project = new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null);
        final String rendered = Containerfile.render(project);
        final String recipe = rendered.lines()
                .filter(line -> line.startsWith("LABEL org.fuin.sokar.recipe="))
                .findFirst().orElseThrow();

        assertThat(recipe).contains(Containerfile.fingerprint(project));
        assertThat(rendered).contains("ENV LANG=C.UTF-8");

        // The body is in the digest: a project whose base image and snippet are identical but
        // whose rendered recipe differs must not share a fingerprint.
        final Project other = new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04",
                "RUN echo different");
        assertThat(Containerfile.fingerprint(other))
                .as("a different recipe is a different fingerprint")
                .isNotEqualTo(Containerfile.fingerprint(project));
    }

    @Test
    void theAgentsLayersAreStillNotInTheFingerprint() {

        // Unchanged rule, restated as a test because the fingerprint now hashes a rendering and
        // it would be easy to hash the wrong one: which agent runs is chosen per task, so an
        // image is not stale because somebody picked a different agent.
        final Project project = new Project("uc", "", SecurityClass.GUARDED, "ubuntu:24.04", null);
        final String withoutLayers = Containerfile.fingerprint(project);

        assertThat(Containerfile.render(project, ImageLayers.none()))
                .contains(withoutLayers);
    }
}
