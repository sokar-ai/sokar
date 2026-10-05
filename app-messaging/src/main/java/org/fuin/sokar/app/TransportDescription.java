package org.fuin.sokar.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.wire.Json;

/**
 * What a transport says about itself.
 * <p>
 * Asked rather than configured: an adapter is a package that arrives without Sokar being rebuilt, so
 * anything Sokar needs to know about it has to come from the adapter. Everything here is a claim,
 * and the two the host acts on are checked against what actually happens - a transport that says it
 * polls and fetches nothing has fetched nothing, and one that says it attests an owner and does not
 * write the file has its messages held.
 *
 * @param scheme The part before the colon in a peer's address.
 * @param polls Whether {@code poll} is worth calling at all.
 * @param attests What it proves about a message it hands over. Empty for one that carries bytes.
 * @param confirms How far it can confirm a message: {@code handover}, {@code receipt} or {@code read}.
 * @param lifecycle The lifecycle verbs it offers - {@code setup}, {@code enroll}, {@code retire},
 *        {@code join} - empty for a transport that keeps no conversation of its own.
 * @param takes The options it takes beyond the ones every transport does - {@code machine} for this
 *        machine's name on its lifecycle verbs. An option it does not list is never passed: an unknown one
 *        is its 64.
 */
public record TransportDescription(String scheme, boolean polls, List<String> attests, String confirms,
        List<String> lifecycle, List<String> takes) {

    /** What is assumed of a transport that cannot be asked: the least, so nothing is granted. */
    public static final TransportDescription UNKNOWN =
            new TransportDescription("", false, List.of(), "", List.of(), List.of());

    /**
     * Constructor for a transport with no lifecycle, the shape before transports kept a conversation.
     *
     * @param scheme The part before the colon in a peer's address.
     * @param polls Whether {@code poll} is worth calling at all.
     * @param attests What it proves about a message it hands over.
     */
    public TransportDescription(String scheme, boolean polls, List<String> attests) {
        this(scheme, polls, attests, "", List.of(), List.of());
    }

    /**
     * Constructor for a transport that takes no options beyond the common ones.
     *
     * @param scheme The part before the colon in a peer's address.
     * @param polls Whether {@code poll} is worth calling at all.
     * @param attests What it proves about a message it hands over.
     * @param confirms How far it can confirm a message.
     * @param lifecycle The lifecycle verbs it offers.
     */
    public TransportDescription(String scheme, boolean polls, List<String> attests, String confirms,
            List<String> lifecycle) {
        this(scheme, polls, attests, confirms, lifecycle, List.of());
    }

    /**
     * Asks a transport to describe itself.
     *
     * @param runner How commands are run.
     * @param adapter The transport's executable.
     * @return What it said, or {@link #UNKNOWN} when it could not be asked or did not answer with
     *         an object. Refusing to guess: a transport whose description cannot be read is not
     *         polled and attests nothing.
     */
    public static TransportDescription of(final CommandRunner runner, final Path adapter) {
        final CommandResult result;
        try {
            result = runner.run(HelperEnvironment.chosen(Command.of(adapter.toString(), "describe")));
        } catch (final RuntimeException ex) {
            return UNKNOWN;
        }
        if (!result.successful()) {
            return UNKNOWN;
        }
        try {
            if (Json.parse(result.standardOutput()) instanceof Map<?, ?> said) {
                final List<String> attests = said.get("attests") instanceof List<?> listed
                        ? listed.stream().map(String::valueOf).toList() : List.of();
                final List<String> lifecycle = said.get("lifecycle") instanceof List<?> verbs
                        ? verbs.stream().map(String::valueOf).toList() : List.of();
                final List<String> takes = new java.util.ArrayList<>(said.get("takes") instanceof List<?> options
                        ? options.stream().map(String::valueOf).toList() : List.of());
                // Announced as its own field: it shows a person a line of Sokar's, passed as --shown.
                if (Boolean.TRUE.equals(said.get(TransportSend.SHOWN))) {
                    takes.add(TransportSend.SHOWN);
                }
                // And a person's words in the room, and a task's direct chats.
                for (final String flag : List.of(TransportConversations.PERSONS, TransportConversations.DIRECT,
                        TransportSend.MENTION, TransportConversations.WAITS)) {
                    if (Boolean.TRUE.equals(said.get(flag))) {
                        takes.add(flag);
                    }
                }
                return new TransportDescription(
                        said.get("scheme") instanceof String scheme ? scheme : "",
                        Boolean.TRUE.equals(said.get("poll")), attests,
                        said.get("confirms") instanceof String confirms ? confirms : "", lifecycle, takes);
            }
        } catch (final RuntimeException ex) {
            return UNKNOWN;
        }
        return UNKNOWN;
    }

    /**
     * Says whether this transport promised to prove who owned a message.
     *
     * @return {@code true} when {@code attests} lists it.
     */
    public boolean attestsOwner() {
        return attests.contains(OwnerAttestation.ATTESTS);
    }

    /**
     * Says whether this transport keeps a conversation of its own - a room - that Sokar sets up and enrolls
     * tasks into.
     *
     * @return {@code true} when it offers {@code setup}.
     */
    public boolean keepsConversation() {
        return lifecycle.contains("setup");
    }

    /**
     * Says whether this transport offers a lifecycle verb.
     *
     * @param verb {@code setup}, {@code enroll}, {@code retire} or {@code join}.
     * @return {@code true} when it listed it.
     */
    public boolean offers(String verb) {
        return lifecycle.contains(verb);
    }

    /**
     * Says whether this transport takes an option beyond the common ones.
     *
     * @param option {@code machine}.
     * @return {@code true} when it listed it.
     */
    public boolean takes(String option) {
        return takes.contains(option);
    }
}
