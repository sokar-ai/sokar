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
 */
public record TransportDescription(String scheme, boolean polls, List<String> attests) {

    /** What is assumed of a transport that cannot be asked: the least, so nothing is granted. */
    public static final TransportDescription UNKNOWN =
            new TransportDescription("", false, List.of());

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
            result = runner.run(Command.of(adapter.toString(), "describe"));
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
                return new TransportDescription(
                        said.get("scheme") instanceof String scheme ? scheme : "",
                        Boolean.TRUE.equals(said.get("poll")), attests);
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
}
