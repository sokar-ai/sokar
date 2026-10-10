package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decided on 2026-10-04: every agent reads every message in the room, a person addresses one with
 * {@code @agentname} or in a direct chat, and a person's words are not filtered.
 */
class EveryoneReadsTheRoomTest {

    @TempDir
    private Path dir;

    private Mailbox mailbox(final String container) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve(container));
        mailbox.create();
        return mailbox;
    }

    private List<TransportConversations.Member> members(final Mailbox writer, final Mailbox review) {
        return List.of(new TransportConversations.Member("sokar-p-writer", writer, "p", "matrix"),
                new TransportConversations.Member("sokar-p-review", review, "p", "matrix"));
    }

    private static final Map<String, String> ACCOUNTS = Map.of("sokar-p-writer", "@sokar-p-writer:localhost",
            "sokar-p-review", "@sokar-p-review:localhost");

    private static final Map<String, String> PEOPLE = Map.of("michi", "@michi:localhost");

    private static void person(final Path inbound, final String name, final String json) throws IOException {
        Files.createDirectories(inbound);
        Files.writeString(inbound.resolve(name), json, StandardCharsets.UTF_8);
    }

    @Test
    void aPersonsWordsInTheRoomReachEveryTaskAsTheirsAndNameWhomTheyAreFor() throws IOException {
        final Mailbox writer = mailbox("sokar-p-writer");
        final Mailbox review = mailbox("sokar-p-review");
        final Path inbound = dir.resolve("inbound");
        person(inbound, "t1--matrix-person-a.json", "{\"sender\":\"@michi:localhost\","
                + "\"to\":[\"@sokar-p-writer:localhost\"],\"body\":\"look at the failing test\",\"eventId\":\"$e1\","
                + "\"at\":\"2026-10-04T07:30:00Z\"}");
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        TransportConversations.handOut(inbound, members(writer, review), ACCOUNTS, PEOPLE, handed, said);

        for (final Mailbox each : List.of(writer, review)) {
            final Path arrived = each.inbound().resolve("t1--matrix-person-a.json");
            assertThat(arrived).as("every agent reads the room").exists();
            assertThat(each.inbound().resolve("t1--matrix-person-a.json" + MessageDelivery.PERSON_SUFFIX))
                    .hasContent("michi");
            assertThat(arrived).content().contains("\"role\":\"ROLE_USER\"").contains("look at the failing test")
                    .contains("\"from\":\"michi\"").contains("\"to\":[\"writer\"]").contains("\"via\":\"room\"");
        }
        try (var left = Files.list(inbound)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void aDirectChatReachesOnlyTheTaskItIsWith() throws IOException {
        final Mailbox writer = mailbox("sokar-p-writer");
        final Mailbox review = mailbox("sokar-p-review");
        final Path inbound = dir.resolve("inbound");
        person(inbound, "t2--matrix-person-b.json", "{\"sender\":\"@michi:localhost\",\"direct\":true,"
                + "\"to\":[\"@sokar-p-writer:localhost\"],\"body\":\"just between us\",\"eventId\":\"$e2\"}");

        TransportConversations.handOut(inbound, members(writer, review), ACCOUNTS, PEOPLE, new LinkedHashMap<>(),
                new java.util.ArrayList<>());

        assertThat(writer.inbound().resolve("t2--matrix-person-b.json")).content().contains("\"direct\":true")
                .contains("\"via\":\"direct\"");
        assertThat(review.inbound().resolve("t2--matrix-person-b.json")).doesNotExist();
    }

    @Test
    void somebodyWhoIsNotInTheProjectsConversationReachesNobody() throws IOException {
        final Mailbox writer = mailbox("sokar-p-writer");
        final Mailbox review = mailbox("sokar-p-review");
        final Path inbound = dir.resolve("inbound");
        person(inbound, "t3--matrix-person-c.json",
                "{\"sender\":\"@stranger:localhost\",\"to\":[],\"body\":\"hello\",\"eventId\":\"$e3\"}");
        final List<String> said = new java.util.ArrayList<>();

        TransportConversations.handOut(inbound, members(writer, review), ACCOUNTS, PEOPLE, new LinkedHashMap<>(), said);

        assertThat(writer.inbound().resolve("t3--matrix-person-c.json")).doesNotExist();
        assertThat(said).singleElement().asString().contains("@stranger:localhost")
                .contains("not in the project's conversation");
    }

    @Test
    void aTasksMessageInTheRoomReachesEveryOtherTaskButNotItsSender() throws IOException {
        final Mailbox writer = mailbox("sokar-p-writer");
        final Mailbox review = mailbox("sokar-p-review");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        Files.writeString(inbound.resolve("$m.json"), "{\"metadata\":{\"to\":\"people\"}}");
        Files.writeString(inbound.resolve("$m.json.sig"), "sig");
        Files.writeString(inbound.resolve("$m.json.sender"), "@sokar-p-writer:localhost");

        TransportConversations.handOut(inbound, members(writer, review), ACCOUNTS, PEOPLE, new LinkedHashMap<>(),
                new java.util.ArrayList<>());

        assertThat(review.inbound().resolve("$m.json")).exists();
        assertThat(review.inbound().resolve("$m.json.sig")).exists();
        assertThat(writer.inbound().resolve("$m.json")).as("its own, back").doesNotExist();
    }

    @Test
    void aPersonsWordsReachTheAgentUnsignedAndUnfilteredAndAreRecordedAsTheirs() throws IOException {
        final Mailbox writer = mailbox("sokar-p-writer");
        Files.writeString(writer.inbound().resolve("t1--matrix-person-a.json"), "{\"messageId\":\"person-$e1\","
                + "\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hi\"}],\"metadata\":{\"from\":\"michi\"}}");
        Files.writeString(writer.inbound().resolve("t1--matrix-person-a.json" + MessageDelivery.PERSON_SUFFIX), "michi");
        // A filter that would refuse anything: a person's words never meet it.
        final org.fuin.sokar.testing.FakeCommandRunner refusing = new org.fuin.sokar.testing.FakeCommandRunner()
                .failing("", 1, "refused");

        final MessageDelivery.Outcome outcome = new MessageDelivery().deliver(writer, List.of(), java.util.Set.of(),
                new InboundCheck(refusing, dir.resolve("filter"), org.fuin.sokar.core.project.Mail.none()));

        assertThat(outcome.held()).isEmpty();
        assertThat(outcome.delivered()).singleElement().satisfies(delivered -> {
            assertThat(delivered.peer()).isEqualTo("michi");
            assertThat(delivered.id()).isEqualTo("person-$e1");
        });
        assertThat(writer.inboxNew().resolve("t1--matrix-person-a.json")).exists();
        assertThat(refusing.invocations()).isEmpty();
        assertThat(writer.inbound().resolve("t1--matrix-person-a.json" + MessageDelivery.PERSON_SUFFIX)).doesNotExist();
    }

    @Test
    void aMessageWithoutASignatureThatNoTransportAttestedAsAPersonsIsHeldAsBefore() throws IOException {
        final Mailbox writer = mailbox("sokar-p-writer");
        Files.writeString(writer.inbound().resolve("m-1.json"), "{\"messageId\":\"m-1\"}");

        final MessageDelivery.Outcome outcome = new MessageDelivery().deliver(writer, List.of());

        assertThat(outcome.held()).singleElement().extracting(MessageDelivery.Held::reason).asString()
                .contains("without a signature");
    }
}
