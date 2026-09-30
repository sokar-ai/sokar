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

    @Test
    void a_peer_can_be_reached_through_the_projects_own_conversation() {
        final Project project = read(HEAD + """
                mail:
                  transports:
                    matrix:
                      homeserver: https://matrix.example.org
                      tls_verify: on
                  peers:
                    reviewer: { address: "matrix:", trust: vouched }
                    ops: { address: "mail:ops@example.org" }
                """);

        assertThat(project.mail().peer("reviewer").conversation()).isTrue();
        assertThat(project.mail().peer("ops").conversation()).isFalse();
        assertThat(project.mail().conversations()).containsExactly("matrix");
        // Handed to the transport as written: Sokar reads none of it.
        assertThat(project.mail().transports()).containsOnlyKeys("matrix");
        assertThat(project.mail().transports().get("matrix")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(Object.class, Object.class))
                .containsEntry("homeserver", "https://matrix.example.org").containsEntry("tls_verify", true);
    }

    @Test
    void a_transports_settings_are_a_mapping() {
        assertThatThrownBy(() -> read(HEAD + "mail:\n  transports:\n    matrix: yes\n"))
                .hasMessageContaining("'mail.transports.matrix' must be a mapping");
    }

    @Test
    void a_setting_in_the_wrong_place_is_refused_and_named_where_it_belongs() {
        // Found by Agent Matrix: under 'mail:', one section too low, followed without a word and never read.
        assertThatThrownBy(() -> read(HEAD + "mail:\n  unread_work_may_leave: true\n"))
                .hasMessageContaining("'mail.unread_work_may_leave' is not a setting; it belongs under 'project:'");
        assertThatThrownBy(() -> read(HEAD.replace("security_class", "securty_class")
                + "  security_class: \"guarded\"\n"))
                .hasMessageContaining("'project.securty_class' is not a setting; did you mean 'security_class'?");
        assertThatThrownBy(() -> read(HEAD + "mail:\n  peers:\n    r: { address: \"matrix:\", trusted: vouched }\n"))
                .hasMessageContaining("'mail.peers.r.trusted' is not a setting; did you mean 'trust'?");
        // Unknown and near nothing: a later Sokar's, accepted and warned about.
        assertThat(read(HEAD + "gate:\n  on: true\n").name()).isEqualTo("p");
        assertThat(ProjectReader.unknownKeys(new StringReader(HEAD + "gate:\n  on: true\n"), "test"))
                .containsExactly("gate");
    }

    @Test
    void what_is_the_projects_own_or_a_transports_is_not_checked() {
        final Project project = read(HEAD + """
                credentials:
                  anything-at-all: somewhere
                repositories:
                  backend: { upstream: "git@example.org:b.git", limits: { memory: "4g" } }
                mail:
                  transports:
                    matrix: { whatever_the_transport_takes: 1 }
                """);
        assertThat(project.credentials()).containsKey("anything-at-all");
    }
}
