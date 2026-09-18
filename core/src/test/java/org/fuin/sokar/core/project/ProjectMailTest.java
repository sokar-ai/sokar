package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * What a project says about the peers its tasks may address.
 */
class ProjectMailTest {

    private static final String HEAD = """
            project:
              name: "p"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    private Project read(final String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test");
    }

    @Test
    void a_project_without_the_section_addresses_nobody() {
        assertThat(read(HEAD).mail().peers()).isEmpty();
    }

    @Test
    void reads_a_peer_with_its_transport_and_trust() {
        final Project project = read(HEAD + """
                mail:
                  peers:
                    reviewer:
                      address: "local:/home/michi/.local/state/sokar/mail/sokar-p-r/inbound"
                      trust: "vouched"
                """);

        final Mail.Peer peer = project.mail().peer("reviewer");
        assertThat(peer).isNotNull();
        assertThat(peer.transport()).isEqualTo("local");
        assertThat(peer.destination()).endsWith("/inbound");
        assertThat(peer.trust()).isEqualTo(Mail.Peer.VOUCHED);
        assertThat(project.mail().peer("nobody")).isNull();
    }

    /**
     * Vouched is the narrower promise - it is what skips the check on the way in - so leaving the
     * trust out has to give the wider answer rather than the convenient one.
     */
    @Test
    void a_peer_whose_trust_is_left_out_is_external() {
        final Project project = read(HEAD + """
                mail:
                  peers:
                    ops:
                      address: "mail:ops@example.org"
                """);

        assertThat(project.mail().peer("ops").trust()).isEqualTo(Mail.Peer.EXTERNAL);
    }

    @Test
    void refuses_an_address_without_a_transport() {
        assertThatThrownBy(() -> read(HEAD + """
                mail:
                  peers:
                    ops:
                      address: "ops@example.org"
                """)).isInstanceOf(ProjectException.class).hasMessageContaining("<transport>:");
    }

    @Test
    void refuses_a_trust_level_nobody_defined() {
        assertThatThrownBy(() -> read(HEAD + """
                mail:
                  peers:
                    ops:
                      address: "mail:ops@example.org"
                      trust: "probably"
                """)).isInstanceOf(ProjectException.class).hasMessageContaining("probably");
    }

    @Test
    void refuses_a_peer_that_is_not_a_mapping() {
        assertThatThrownBy(() -> read(HEAD + """
                mail:
                  peers:
                    ops: "mail:ops@example.org"
                """)).isInstanceOf(ProjectException.class).hasMessageContaining("mail.peers.ops");
    }
}
