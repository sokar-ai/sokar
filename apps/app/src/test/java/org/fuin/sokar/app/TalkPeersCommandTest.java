package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.project.Mail;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link TalkPeersCommand}: whom a project's tasks reach, including each other.
 */
class TalkPeersCommandTest {

    @Test
    void aProjectWithAConversationSaysItsTasksReachEachOther() {

        // It listed only the written peers, and "no peers - this project's tasks address nobody" when there were
        // none, while its tasks reached each other by name through the conversation.
        assertThat(TalkPeersCommand.tasks(new Mail(List.of(), Map.of("matrix", Map.of()))))
                .contains("each other").contains("matrix");
    }

    @Test
    void aStandaloneProjectSaysItsTasksDoNot() {
        assertThat(TalkPeersCommand.tasks(Mail.none())).contains("do not message each other");
    }

    @Test
    void listsThePeopleOfTheConversationAndEachMemberBesideTheFilesPeers() {

        // Found by Agent Matrix on 2026-10-04: 'people' and the room's members worked as addresses and were not
        // listed, so a person reading the list could not tell they were there.
        final List<Mail.Peer> listed = TalkPeersCommand.listed(new Mail(List.of(new Mail.Peer("ops",
                "mail:ops@example.org", Mail.Peer.EXTERNAL)), Map.of("matrix", Map.of())),
                Map.of("michi", "@michi:localhost"));

        assertThat(listed).extracting(Mail.Peer::name).containsExactly("ops", Mail.PEOPLE, "michi");
        assertThat(listed.get(2).destination()).isEqualTo("@michi:localhost");
    }
}
