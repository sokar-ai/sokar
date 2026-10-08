package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

class ProjectBuildsTest {

    private static final String ONLINE = """
            project:
              name: "uc"
              security_class: "%s"
              upstream: "git@github.com:example/uc.git"
            image:
              base_image: "ubuntu:24.04"
            """;

    private static Project read(final String securityClass, final String builds) {
        return ProjectReader.read(new StringReader(ONLINE.formatted(securityClass) + builds), "test.yml");
    }

    @Test
    void anOnlineProjectNamesItsForgeAndTheVaultEntryItsBuildsAreReadWith() {
        final Builds builds = read("online", """
                builds:
                  forge: "github"
                  credential: "github-actions"
                """).builds();

        assertThat(builds).isEqualTo(new Builds("github", "github-actions", "", Builds.FAILURE));
    }

    @Test
    void aProjectWithoutTheSectionFollowsNoBuild() {
        assertThat(read("online", "").builds()).isNull();
    }

    @Test
    void guardedAndOfflineRefuseItBeforeATaskStarts() {
        for (final String securityClass : new String[] {"guarded", "offline"}) {
            assertThatThrownBy(() -> read(securityClass, "builds:\n  forge: \"github\"\n  credential: \"x\"\n"))
                    .as(securityClass).isInstanceOf(ProjectException.class)
                    .hasMessageContaining("online projects only");
        }
    }

    @Test
    void takesEveryLogWhenAskedAndNothingElse() {
        assertThat(read("online", "builds:\n  forge: \"github\"\n  credential: \"x\"\n  logs: \"all\"\n").builds()
                .logs()).isEqualTo(Builds.ALL);
        assertThatThrownBy(() -> read("online", "builds:\n  forge: \"github\"\n  credential: \"x\"\n  logs: \"some\"\n"))
                .isInstanceOf(ProjectException.class).hasMessageContaining("'failure' or 'all'");
    }

    @Test
    void refusesAForgeThatIsNoReadersNameAnApiThatIsNotHttpsAndNoCredential() {
        assertThatThrownBy(() -> read("online", "builds:\n  forge: \"../bin/sh\"\n  credential: \"x\"\n"))
                .isInstanceOf(ProjectException.class).hasMessageContaining("must name a build reader");
        assertThatThrownBy(() -> read("online",
                "builds:\n  forge: \"github\"\n  credential: \"x\"\n  api: \"http://forge.example\"\n"))
                .isInstanceOf(ProjectException.class).hasMessageContaining("https://");
        assertThatThrownBy(() -> read("online", "builds:\n  forge: \"github\"\n"))
                .isInstanceOf(ProjectException.class).hasMessageContaining("vault entry");
    }

    @Test
    void everyKeyOfTheSectionIsOneTheSchemaDeclares() {
        assertThat(ProjectReader.unknownKeys(new StringReader(ONLINE.formatted("online")
                + "builds:\n  forge: \"github\"\n  credential: \"x\"\n  api: \"https://ghe.example/api/v3\"\n"
                + "  logs: \"all\"\n"), "test.yml")).isEmpty();
    }
}
