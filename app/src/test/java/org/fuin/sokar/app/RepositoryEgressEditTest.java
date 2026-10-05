package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import org.junit.jupiter.api.Test;

/**
 * Writing a grant back into the repository it belongs to, rather than into the project.
 * <p>
 * <strong>This is what decides whether a repository's own egress is worth anything.</strong>
 * A repository may declare its own egress, but if allowing a blocked connection still remembers it
 * at project level, then every project drifts back to one wide grant the first time somebody
 * presses allow - and nothing on screen would say so.
 * <p>
 * Every case here checks the file the operator has to read afterwards, not a model: the comments,
 * the quoting and the key order in it are the reason this is a text edit at all.
 */
class RepositoryEgressEditTest {

    private static final String FILE = """
            # Sokar's own project.
            project:
              name: "acme"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"

            # What every repository needs.
            egress:
              sets: [maven]

            repositories:

              # Drives Flutter through Maven.
              frontend:
                upstream: "git@example.com:acme/frontend.git"
                egress:
                  sets: [flutter]

              # Work that stays on this machine.
              scratch:

              backend:
                upstream: "git@example.com:acme/backend.git"
            """;

    private static Project read(String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test");
    }

    @Test
    void addsToTheRepositorysOwnBlockAndLeavesTheProjectsAlone() {

        final String edited = EgressEdit.withEgress(FILE, "frontend",
                List.of("flutter"), List.of("pub.dev"));
        final Project after = read(edited);

        assertThat(after.repository("frontend").egress().domains()).containsExactly("pub.dev");
        // The project's block is untouched, so no other repository gained anything.
        assertThat(after.egress().sets()).containsExactly("maven");
        assertThat(after.egress().domains()).isEmpty();
        assertThat(after.repository("backend").egress().isEmpty()).isTrue();
    }

    @Test
    void creatingARepositorysBlockTakesNothingAwayFromIt() {

        // The case the additive rule was chosen for. With replacement, this one call would have
        // given the backend 'nexus.corp.example' and taken 'maven' away in the same breath.
        final String edited = EgressEdit.withEgress(FILE, "backend",
                List.of(), List.of("nexus.corp.example"));
        final Project after = read(edited);

        assertThat(after.repository("backend").egress().domains())
                .containsExactly("nexus.corp.example");
        assertThat(after.egressFor(after.repository("backend")).sets()).contains("maven");
    }

    @Test
    void writesUnderARepositoryThatHasNothingUnderItYet() {

        // A bare 'scratch:' is a repository with no upstream, which is legitimate - and it has no
        // mapping to insert into, so the block has to be created rather than found.
        final String edited = EgressEdit.withEgress(FILE, "scratch",
                List.of("maven"), List.of());
        final Project after = read(edited);

        assertThat(after.repository("scratch").egress().sets()).containsExactly("maven");
        assertThat(after.repository("scratch").hasUpstream()).isFalse();
    }

    @Test
    void leavesEveryCommentAndEveryQuoteWhereItWas() {

        final String edited = EgressEdit.withEgress(FILE, "frontend",
                List.of("flutter"), List.of("pub.dev"));

        assertThat(edited).contains("# Sokar's own project.")
                .contains("# What every repository needs.")
                .contains("# Drives Flutter through Maven.")
                .contains("# Work that stays on this machine.")
                .contains("  name: \"acme\"")
                .contains("    upstream: \"git@example.com:acme/frontend.git\"");
    }

    @Test
    void doesNotTouchTheProjectsOwnEgressLine() {

        final String edited = EgressEdit.withEgress(FILE, "frontend",
                List.of("flutter"), List.of("pub.dev"));

        // Found by looking at the file rather than at the model: the top-level block being
        // rewritten at all is the failure this guards against, and a model would only show its
        // value.
        assertThat(edited.lines().filter(line -> line.equals("  sets: [maven]")).count())
                .isEqualTo(1);
    }

    @Test
    void aRepositoryTheFileDoesNotNameIsRefusedRatherThanWrittenToTheProject() {

        // Falling back to the project's block would put the grant in the one place this exists to
        // keep it out of, and it would look as though it had worked.
        assertThatThrownBy(() -> EgressEdit.withEgress(FILE, "nowhere",
                List.of(), List.of("pub.dev")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nowhere");
    }

    @Test
    void aFileWithNoRepositoriesAtAllIsRefusedForANamedRepository() {

        final String plain = """
                project:
                  name: "acme"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """;

        assertThatThrownBy(() -> EgressEdit.withEgress(plain, "frontend",
                List.of(), List.of("pub.dev")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no repositories");
    }

    @Test
    void nullMeansTheProjectsOwnBlock() {

        // The project's own repository has no block of its own: its egress IS the project's.
        final String edited = EgressEdit.withEgress(FILE, null,
                List.of("maven"), List.of("nexus.corp.example"));
        final Project after = read(edited);

        assertThat(after.egress().domains()).containsExactly("nexus.corp.example");
        assertThat(after.repository("frontend").egress().sets()).containsExactly("flutter");
    }

    @Test
    void removingTheLastGrantRemovesTheRepositorysBlockRatherThanLeavingABareKey() {

        final String edited = EgressEdit.withEgress(FILE, "frontend", List.of(), List.of());
        final Project after = read(edited);

        assertThat(after.repository("frontend").egress().isEmpty()).isTrue();
        // A bare 'egress:' reads as a declaration and means nothing, which is the ambiguity this
        // key exists to avoid - the same rule the project's own block follows.
        assertThat(edited).doesNotContain("    egress:");
    }
}
