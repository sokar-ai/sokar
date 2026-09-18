package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * What a repository may say about its own egress and its own limits, and how it combines with the
 * project's.
 * <p>
 * <strong>Two rules, on purpose.</strong> Egress is added and a limit replaces, because egress is a
 * list of grants - where adding is the only operation that makes sense - and a limit is one number
 * with nothing to add it to. Most of what is checked here is the second rule's trap: a key nobody
 * wrote means <em>the project's</em>, never the default.
 */
class RepositoryEgressAndLimitsTest {

    private static Project read(String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test");
    }

    /** A project that raises memory and pids away from the defaults, so a fallback is visible. */
    private static final String HEAD = """
            project:
              name: "acme"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            limits:
              memory: "16g"
              cpus: "4.0"
              pids: 4096
            egress:
              sets: [maven]
              domains: ["nexus.corp.example"]
            """;

    @Test
    void aRepositoryThatSaysNothingRunsExactlyAsTheProjectSays() {
        final Project project = read(HEAD + """
                repositories:
                  backend:
                """);
        final Repository backend = project.repository("backend");

        assertThat(backend.addsNothing()).isTrue();
        assertThat(project.egressFor(backend)).isEqualTo(project.egress());
        assertThat(project.limitsFor(backend)).isEqualTo(project.limits());
    }

    @Test
    void aRepositorysEgressIsAddedToTheProjects() {
        final Project project = read(HEAD + """
                repositories:
                  frontend:
                    egress:
                      sets: [flutter]
                      domains: ["pub.dev"]
                """);
        final Egress reachable = project.egressFor(project.repository("frontend"));

        // Added, not replacing. The frontend drives Flutter through Maven, so losing 'maven' here
        // would break the build in a way that reads as "flutter is broken".
        assertThat(reachable.sets()).containsExactly("maven", "flutter");
        assertThat(reachable.domains()).containsExactly("nexus.corp.example", "pub.dev");
    }

    @Test
    void aSetNamedInBothPlacesIsNamedOnce() {
        final Project project = read(HEAD + """
                repositories:
                  frontend:
                    egress:
                      sets: [maven, flutter]
                """);
        assertThat(project.egressFor(project.repository("frontend")).sets())
                .containsExactly("maven", "flutter");
    }

    @Test
    void oneRepositorysEgressIsNotAnothers() {
        final Project project = read(HEAD + """
                repositories:
                  frontend:
                    egress:
                      sets: [flutter]
                  backend:
                """);
        // The whole point: a task on the backend must not reach a package registry it has no
        // business with just because a sibling repository needs it.
        assertThat(project.egressFor(project.repository("backend")).sets())
                .containsExactly("maven");
    }

    @Test
    void aRepositorysLimitReplacesTheProjectsForThatKeyOnly() {
        final Project project = read(HEAD + """
                repositories:
                  frontend:
                    limits:
                      memory: "32g"
                """);
        final Limits limits = project.limitsFor(project.repository("frontend"));

        assertThat(limits.memory()).isEqualTo("32g");
        // And the two it said nothing about are the PROJECT'S, not the defaults. The project
        // raised both away from 8g and 2048 precisely so a fallback to Limits.defaults() fails
        // this rather than passing by coincidence.
        assertThat(limits.cpus()).isEqualTo("4.0");
        assertThat(limits.pids()).isEqualTo(4096);
    }

    @Test
    void aRepositoryNamingOnlyPidsKeepsTheProjectsMemory() {
        final Project project = read(HEAD + """
                repositories:
                  backend:
                    limits:
                      pids: 8192
                """);
        final Limits limits = project.limitsFor(project.repository("backend"));

        assertThat(limits.pids()).isEqualTo(8192);
        assertThat(limits.memory()).isEqualTo("16g");
    }

    @Test
    void aRepositoryCanOptOutOfALimitOnPurpose() {
        final Project project = read(HEAD + """
                repositories:
                  backend:
                    limits:
                      memory: "none"
                """);
        // Distinguishable from saying nothing: saying nothing gives the project's 16g.
        assertThat(project.limitsFor(project.repository("backend")).memory()).isNull();
    }

    @Test
    void aBadSetNameUnderARepositoryIsRefusedLikeOneAtTheTop() {
        assertThatThrownBy(() -> read(HEAD + """
                repositories:
                  frontend:
                    egress:
                      sets: ["Not A Set"]
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Invalid egress set name");
    }

    @Test
    void aProcessLimitThatLeavesNothingToRunIsRefused() {
        assertThatThrownBy(() -> read(HEAD + """
                repositories:
                  backend:
                    limits:
                      pids: 0
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("leaves nothing to run");
    }

    @Test
    void aRepositorysEgressThatIsNotAMappingSaysWhichBlockWasWrong() {
        assertThatThrownBy(() -> read(HEAD + """
                repositories:
                  frontend:
                    egress: [flutter]
                """))
                .isInstanceOf(ProjectException.class)
                // Named, because a project with six repositories has seven egress blocks and
                // "'egress' must be a mapping" would send a reader through all of them.
                .hasMessageContaining("repositories.frontend.egress");
    }
}
