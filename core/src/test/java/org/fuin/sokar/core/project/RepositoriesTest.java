package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * What a project made of several repositories accepts, and what it refuses.
 * <p>
 * The refusals are the point. A project is a unit of work over one or more repositories, and the
 * ways that can go wrong are all the same shape: a name meaning two different repositories, or a
 * repository nothing can be cloned from - both of which would be found inside a container, minutes
 * later, as a git error against the wrong history.
 */
class RepositoriesTest {

    private static Project read(String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test");
    }

    private static final String HEAD = """
            project:
              name: "acme"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    @Test
    void aProjectThatNamesNoneStillHasItsOwn() {
        final Project project = read(HEAD);
        assertThat(project.repositories()).isEmpty();
        assertThat(project.repositoryNames()).containsExactly("acme");
        assertThat(project.ownRepository().name()).isEqualTo("acme");
    }

    @Test
    void theProjectsOwnRepositoryIsOfferedFirst() {
        final Project project = read(HEAD + """
                repositories:
                  frontend:
                    upstream: "git@example.com:acme/frontend.git"
                  backend:
                    upstream: "git@example.com:acme/backend.git"
                """);
        // Its own first, then the file's own order - not sorted. Somebody who wrote the backend
        // first meant something by it, and a listing that reordered them would be a listing they
        // have to read twice.
        assertThat(project.repositoryNames()).containsExactly("acme", "frontend", "backend");
    }

    @Test
    void theProjectsOwnUpstreamIsItsOwnRepositorysUpstream() {
        final Project project = read("""
                project:
                  name: "acme"
                  security_class: "guarded"
                  upstream: "git@example.com:acme/acme.git"
                image:
                  base_image: "ubuntu:24.04"
                """);
        assertThat(project.repository("acme").upstream())
                .isEqualTo("git@example.com:acme/acme.git");
    }

    @Test
    void aRepositoryNamedAfterTheProjectIsRefused() {
        // That name belongs to the project's own repository. Accepting it would give one name two
        // mirrors, and a task would get whichever one was looked up first.
        assertThatThrownBy(() -> read(HEAD + """
                repositories:
                  acme:
                    upstream: "git@example.com:acme/other.git"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("the project's own repository");
    }

    @Test
    void aRepositoryWithNoUpstreamIsOneWhoseWorkStaysHere() {
        final Project project = read(HEAD + """
                repositories:
                  scratch:
                """);
        assertThat(project.repository("scratch")).isNotNull();
        assertThat(project.repository("scratch").hasUpstream()).isFalse();
    }

    @Test
    void anOnlineProjectNeedsAnUpstreamForEveryRepository() {
        // Online means the agent's own remote IS the upstream, so a repository without one is a
        // repository nothing can be cloned from. Refused here rather than in a container.
        assertThatThrownBy(() -> read("""
                project:
                  name: "acme"
                  security_class: "online"
                  upstream: "git@example.com:acme/acme.git"
                image:
                  base_image: "ubuntu:24.04"
                repositories:
                  backend:
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("backend");
    }

    @Test
    void aRepositoryNameThatCannotBeADirectoryIsRefused() {
        assertThatThrownBy(() -> read(HEAD + """
                repositories:
                  "../escape":
                    upstream: "git@example.com:acme/x.git"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Invalid repository name");
    }

    @Test
    void askingForARepositoryTheProjectDoesNotHaveAnswersNothing() {
        assertThat(read(HEAD).repository("nowhere")).isNull();
    }

    @Test
    void theSameNameTwiceIsRefusedByTheFileItself() {
        // YAML refuses it before the reader sees it, which is the earliest anybody could. Checked
        // so that a change to the loader options cannot quietly make the last entry win.
        assertThatThrownBy(() -> read(HEAD + """
                repositories:
                  backend:
                    upstream: "git@example.com:acme/one.git"
                  backend:
                    upstream: "git@example.com:acme/two.git"
                """))
                .isInstanceOf(ProjectException.class);
    }

    @Test
    void repositoriesThatAreNotAMappingAreRefused() {
        // A list is the plausible wrong guess, so it gets a message rather than a class cast.
        assertThatThrownBy(() -> read(HEAD + """
                repositories:
                  - backend
                  - frontend
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("mapping of name to repository");
    }
}
