package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Whose signed commits change a project's file, as the file names them.
 */
class ProjectSignersTest {

    private static final String KEY = "AAAAC3NzaC1lZDI1NTE5AAAAIFPT6TEMXZU/YPtwCsnoTJVBqYGIsHEhIxxswRwI/ebY";

    private static String file(final String signers) {
        return """
                project:
                  name: "p"
                  security_class: "guarded"
                %simage:
                  base_image: "ubuntu:24.04"
                """.formatted(signers);
    }

    @Test
    void a_file_that_names_none_has_none() {
        assertThat(ProjectReader.signers(file(""), "test")).isNull();
    }

    @Test
    void reads_each_key_as_its_type_and_key_whatever_stands_around_it() {
        assertThat(ProjectReader.signers(file("""
                  signers:
                    - "ssh-ed25519 %s michael@example.org"
                    - "michael ssh-ed25519 %s"
                """.formatted(KEY, KEY)), "test")).containsExactly("ssh-ed25519 " + KEY);
    }

    @Test
    void an_entry_that_is_no_key_is_refused_and_so_is_the_file() {
        final String bad = file("""
                  signers:
                    - "michael@example.org"
                """);
        assertThatThrownBy(() -> ProjectReader.signers(bad, "test"))
                .hasMessageContaining("'project.signers' entry 1 is not a public key");
        assertThatThrownBy(() -> ProjectReader.read(new StringReader(bad), "test"))
                .isInstanceOf(ProjectException.class);
    }

    @Test
    void a_file_with_signers_is_a_project_like_any_other() {
        assertThat(ProjectReader.read(new StringReader(file("""
                  signers: ["ssh-ed25519 %s"]
                """.formatted(KEY))), "test").name()).isEqualTo("p");
    }
}
