package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What a person wrote in a project's conversation, as a transport handed it over, made a message for the agents.
 * <p>
 * The transport states what the conversation states and reads no meaning into the text: who wrote it, the accounts it
 * named with {@code @agentname} or answered, whether it was said in a direct chat, and the words. This turns the
 * accounts into the names agents address - a task's short name, a person's plain name - and the words into the
 * message an agent reads, {@code ROLE_USER} from that person.
 *
 * @param readers The tasks that read it: every task of the project, or in a direct chat the one it is with.
 * @param person Who wrote it, by the plain name they joined with.
 * @param message The message, as the agents get it.
 * @param refused Why nobody gets it, or {@code null}.
 */
record PersonsWords(List<TransportConversations.Member> readers, @Nullable String person, byte @Nullable [] message,
        @Nullable String refused) {

    /**
     * Reads a person's words as a transport handed them over.
     *
     * @param file The transport's file.
     * @param members The project's tasks on this machine.
     * @param accounts Each task's account, by container.
     * @param people Each person who joined, to their account.
     * @return Whom it is for and what they read, or why nobody gets it.
     * @throws IOException Reading failed.
     */
    static PersonsWords read(final Path file, final List<TransportConversations.Member> members,
            final Map<String, String> accounts, final Map<String, String> people) throws IOException {
        final Object parsed;
        try {
            parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final RuntimeException ex) {
            return refused("it is not a person's message that can be read");
        }
        if (!(parsed instanceof Map<?, ?> said) || !(said.get("sender") instanceof String sender)
                || !(said.get("body") instanceof String body)) {
            return refused("it names no sender or no words");
        }
        final String person = people.entrySet().stream().filter(entry -> entry.getValue().equals(sender))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (person == null) {
            // Only somebody let into the project's conversation: the room is how a person reaches its agents, and an
            // account the transport admitted is not yet somebody the project let in.
            return refused("it is from " + sender + ", who is not in the project's conversation");
        }
        final List<String> to = new ArrayList<>();
        final List<TransportConversations.Member> named = new ArrayList<>();
        if (said.get("to") instanceof List<?> accountsNamed) {
            for (final Object account : accountsNamed) {
                for (final TransportConversations.Member member : members) {
                    if (String.valueOf(account).equals(accounts.get(member.container()))) {
                        named.add(member);
                        final String task = org.fuin.sokar.runtime.ContainerName.taskIn(member.project(),
                                member.container());
                        to.add(task != null ? task : member.container());
                    }
                }
                people.forEach((who, theirs) -> {
                    if (theirs.equals(String.valueOf(account)) && !to.contains(who)) {
                        to.add(who);
                    }
                });
            }
        }
        final boolean direct = Boolean.TRUE.equals(said.get("direct"));
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("from", person);
        metadata.put("to", to);
        // Where it was said, so it is answered there: a room message in the room, a direct one in the direct chat
        // (the operator in walk 10, 2026-10-04: "a private answer only when he was written to privately").
        metadata.put("via", direct ? VIA_DIRECT : VIA_ROOM);
        if (direct) {
            metadata.put("direct", true);
        }
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", "person-" + (said.get("eventId") instanceof String event ? event
                : file.getFileName().toString()));
        message.put("role", MessageSay.ROLE);
        message.put("parts", List.of(new LinkedHashMap<>(Map.of("text", body, "mediaType", "text/plain"))));
        message.put("metadata", metadata);
        return new PersonsWords(direct ? named : members, person,
                Json.write(message).getBytes(StandardCharsets.UTF_8), null);
    }

    /** Said in the project's conversation, where everybody reads it. */
    static final String VIA_ROOM = "room";

    /** Said in a direct chat with the one task. */
    static final String VIA_DIRECT = "direct";

    private static PersonsWords refused(final String why) {
        return new PersonsWords(List.of(), null, null, why);
    }
}
