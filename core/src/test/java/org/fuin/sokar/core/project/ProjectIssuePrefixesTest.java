package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * The issue prefixes each repository of a project owns, as its project file names them.
 */
class ProjectIssuePrefixesTest {

    private static final String HEAD = """
            project:
              name: "p"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    private static Project read(final String repositories) {
        return ProjectReader.read(new StringReader(HEAD + "repositories:\n" + repositories), "test");
    }

    @Test
    void readsTheListOfEachRepositoryInItsOrder() {
        final Project project = read("""
                  core:
                    upstream: "git@github.com:sokar-ai/sokar.git"
                    issues: ["B", "P"]
                  pi:
                    upstream: "git@github.com:sokar-ai/sokar-pi.git"
                    issues: ["PI"]
                  site:
                    upstream: "git@github.com:sokar-ai/sokar-ai.github.io.git"
                """);

        assertThat(project.repository("core").issues()).containsExactly("B", "P");
        assertThat(project.repository("pi").issues()).containsExactly("PI");
        assertThat(project.repository("site").issues()).as("a repository without issues has none").isEmpty();
    }

    @Test
    void refusesAPrefixThatIsNotCapitalLetters() {
        assertThatThrownBy(() -> read("""
                  core:
                    issues: ["B", "p1"]
                """)).isInstanceOf(ProjectException.class)
                .hasMessageContaining("'repositories.core.issues'").hasMessageContaining("'p1'");
    }

    @Test
    void refusesAPrefixTwoRepositoriesName() {
        assertThatThrownBy(() -> read("""
                  core:
                    issues: ["B", "P"]
                  frontend:
                    issues: ["F", "P"]
                """)).isInstanceOf(ProjectException.class)
                .as("the prefix and both repositories named")
                .hasMessageContaining("'P'").hasMessageContaining("core").hasMessageContaining("frontend");
    }

    @Test
    void refusesAnIssuesKeyThatIsNoList() {
        assertThatThrownBy(() -> read("""
                  core:
                    issues: "B"
                """)).isInstanceOf(ProjectException.class)
                .hasMessageContaining("'repositories.core.issues' is a list");
    }
}
