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

    @Test
    void atATerminalAStartWithoutARepositoryAsksWhichOfTheOnesWorkStartsIn() {
        final var project = ProjectReader.read(new StringReader("""
                project: { name: "shop", security_class: "guarded" }
                image: { base_image: "ubuntu:24.04" }
                repositories:
                  api:
                    upstream: "git@forge.example.org:o/api.git"
                  web:
                    upstream: "git@forge.example.org:o/web.git"
                """), "test");
        final java.io.StringWriter err = new java.io.StringWriter();

        assertThat(TaskRunCommand.repositoryChosen(project,
                new Offer(question -> "2", false, false, new java.io.PrintWriter(err, true)))).isEqualTo("web");
        assertThat(err.toString()).contains("1  api").contains("2  web").doesNotContain("shop");
        assertThat(TaskRunCommand.repositoryChosen(project,
                new Offer(question -> "", false, false, new java.io.PrintWriter(err, true)))).as("no answer").isNull();
        assertThat(err.toString()).contains("'sokar task start --repository <name>'");
    }
}
