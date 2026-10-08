package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.fuin.sokar.core.project.Mail;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MessageSay}: a person's words go only where they can arrive.
 */
class MessageSayTest {

    @Test
    void aProjectWithNoConversationRefusesANameItDoesNotKnow() {

        // It answered "written", and the message went nowhere: the project had no conversation and no such peer.
        assertThat(MessageSay.refusal(Mail.none(), "b")).contains("no peer 'b'").contains("no conversation");
    }

    @Test
    void aProjectWithAConversationTakesAnyNameATaskElsewhereMayHave() {

        // A task of the project may run on another machine, so in a conversation a name is not refused here.
        assertThat(MessageSay.refusal(new Mail(java.util.List.of(), Map.of("matrix", Map.of())), "b")).isNull();
    }
}
