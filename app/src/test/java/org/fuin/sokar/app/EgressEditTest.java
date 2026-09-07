package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EgressEdit}, which edits the one file in this product a person hand-writes.
 */
class EgressEditTest {

    private static final String WITH_COMMENTS = """
            # Written by 'sokar task run'. Everything here can be changed.
            project:
              name: "uc"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"

            # What this project's own tooling may reach. Nothing else resolves.
            egress:
              sets: [maven, git-hosting]
            """;

    @Test
    void leavesEveryLineItDoesNotOwnExactlyWhereItWas() {

        // The whole reason this is a text edit: a round trip through a YAML model would drop the
        // comments, the quoting and the key order in the file people review.
        final String edited = EgressEdit.withEgress(WITH_COMMENTS, List.of("maven"), List.of());

        assertThat(edited).contains("# Written by 'sokar task run'.")
                .contains("# What this project's own tooling may reach.")
                .contains("  name: \"uc\"")
                .contains("  base_image: \"ubuntu:24.04\"");
        assertThat(edited.lines().filter(line -> line.startsWith("egress:")).count()).isEqualTo(1);
    }

    @Test
    void replacesTheSetsWhereTheyStand() {

        final String edited = EgressEdit.withEgress(WITH_COMMENTS,
                List.of("maven", "npm"), List.of());

        assertThat(edited).contains("  sets: [maven, npm]").doesNotContain("git-hosting");
    }

    @Test
    void addsAKeyTheFileDidNotHave() {

        final String edited = EgressEdit.withEgress(WITH_COMMENTS, List.of("maven", "git-hosting"),
                List.of("nexus.corp.example"));

        assertThat(edited).contains("  sets: [maven, git-hosting]")
                .contains("  domains: [\"nexus.corp.example\"]");
    }

    @Test
    void writesTheBlockWhenTheFileHasNone() {

        final String edited = EgressEdit.withEgress("""
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """, List.of("maven"), List.of());

        assertThat(edited).endsWith("egress:\n  sets: [maven]\n");
    }

    @Test
    void takesTheWholeBlockAwayWhenNothingIsLeftInIt() {

        // A bare 'egress:' parses as a declaration of nothing, which is exactly the ambiguity the
        // key exists to avoid: an operator would read it as configured.
        final String edited = EgressEdit.withEgress("""
                project:
                  name: "uc"
                egress:
                  sets: [maven]
                image:
                  base_image: "ubuntu:24.04"
                """, List.of(), List.of());

        assertThat(edited).doesNotContain("egress")
                .contains("  name: \"uc\"")
                .contains("  base_image: \"ubuntu:24.04\"");
    }

    @Test
    void normalisesABlockListToTheFormTheWizardWrites() {

        // Both forms parse; one is written back, so a file does not end up with two spellings of
        // the same list after an edit.
        final String edited = EgressEdit.withEgress("""
                project:
                  name: "uc"
                egress:
                  sets:
                    - maven
                    - git-hosting
                  domains: ["nexus.corp.example"]
                """, List.of("maven"), List.of("nexus.corp.example"));

        assertThat(edited).contains("  sets: [maven]")
                .doesNotContain("- git-hosting")
                .contains("  domains: [\"nexus.corp.example\"]");
    }

    @Test
    void leavesAKeyOfTheSameNameThatIsNotThisOne() {

        // 'egress' nested under something else is somebody else's key, whatever it means.
        final String original = """
                project:
                  name: "uc"
                image:
                  egress: "not this one"
                """;

        assertThat(EgressEdit.withEgress(original, List.of("maven"), List.of()))
                .contains("  egress: \"not this one\"")
                .endsWith("egress:\n  sets: [maven]\n");
    }

    @Test
    void keepsAFileWithoutATrailingNewlineValid() {

        assertThat(EgressEdit.withEgress("project:\n  name: \"uc\"", List.of("maven"), List.of()))
                .isEqualTo("project:\n  name: \"uc\"\negress:\n  sets: [maven]\n");
    }
}
