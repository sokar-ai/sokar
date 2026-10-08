package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import org.fuin.sokar.core.project.ProjectReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for what a refusal offers when a repository is not a project's: the ones work can start in, never its own.
 */
class RepositoryOfferTest {

    @Test
    void anEmptyDefaultOffersNoneAndSaysHowToAddOne() {

        // A start said "It has: default" while 'project default list' said it had no repository yet: the project's own
        // repository was offered, where no task works.
        final var empty = ProjectReader.read(new StringReader("""
                project: { name: "default", security_class: "guarded" }
                image: { base_image: "ubuntu:24.04" }
                """), "test");

        assertThat(OwnRepository.worksIn(empty)).doesNotContain("default,").isNotEqualTo("default")
                .contains("none yet").contains("sokar project default add");
    }

    @Test
    void aProjectWithRepositoriesOffersThemAndNotItsOwn() {
        final var project = ProjectReader.read(new StringReader("""
                project: { name: "shop", security_class: "guarded" }
                image: { base_image: "ubuntu:24.04" }
                repositories:
                  api:
                    upstream: "git@forge.example.org:o/api.git"
                  web:
                    upstream: "git@forge.example.org:o/web.git"
                """), "test");

        assertThat(OwnRepository.worksIn(project)).isEqualTo("api, web");
    }
}
