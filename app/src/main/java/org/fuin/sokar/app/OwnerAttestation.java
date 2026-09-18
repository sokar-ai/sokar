package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.project.Mail;

/**
 * Checks what a transport says about who owned an arriving message.
 * <p>
 * Most transports carry bytes and know nothing about their sender; those attest nothing and this
 * has nothing to say about them. A transport that carries between Unix users on one machine is
 * different: the kernel told it who wrote the file, and that is a fact the host cannot recover
 * afterwards, because moving the message out of a spool directory loses the ownership with it.
 * <p>
 * <strong>It is a second, independent fact about one message.</strong> The signature says who wrote
 * the bytes; the owner says who put them where the recipient would find them. Somebody who copies a
 * validly signed message of another peer's into a drop still owns the file they wrote, so the two
 * facts disagree and the message is held. Neither check replaces the other.
 * <p>
 * <strong>Fail closed, in both directions.</strong> A transport that promised {@code owner} and
 * handed over a message without one has its message held - a promise nobody kept is worse than none.
 * A transport that promised nothing has an attestation ignored, so it cannot gain trust by
 * volunteering a file.
 */
public final class OwnerAttestation {

    /** What a transport writes beside a message whose owner it saw. */
    public static final String SUFFIX = ".owner";

    /** What such a transport lists in {@code describe}'s {@code attests}. */
    public static final String ATTESTS = "owner";

    private OwnerAttestation() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Says whether an arriving message may be delivered, as far as ownership goes.
     *
     * @param mailbox The task's mailbox.
     * @param message The message, still in {@code inbound}.
     * @param peer The peer whose key signed it.
     * @param mail The project's peers, which is where an address names a Unix user.
     * @param attesting Whether the transport that brought it promised {@link #ATTESTS}.
     * @return An empty string when it may go on, otherwise why it may not.
     * @throws IOException Reading failed.
     */
    public static String refuse(final Mailbox mailbox, final Path message, final String peer,
            final Mail mail, final boolean attesting) throws IOException {
        final Path attestation =
                mailbox.inbound().resolve(message.getFileName().toString() + SUFFIX);
        if (!attesting) {
            // Not promised, so not read. A file here is somebody's leftovers, not evidence.
            return "";
        }
        if (!Files.isRegularFile(attestation)) {
            return "the transport that carried it says it attests who owned it, and it did not";
        }
        final String said = Files.readString(attestation, StandardCharsets.UTF_8).strip();
        final String[] parts = said.split("\\s+");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return "what the transport said about its owner cannot be read: '" + said + "'";
        }
        final Mail.Peer known = mail.peer(peer);
        if (known == null) {
            // Attribution let it through because a key vouched for it; the project still has to
            // say who that peer is before an owner can be compared with anything.
            return "it is signed for '" + peer + "', which this project does not list, so there is"
                    + " nothing to check its owner against";
        }
        final String expected = known.destination();
        if (!expected.equals(parts[1])) {
            return "it was written by the user '" + parts[1] + "' (uid " + parts[0] + "), and '"
                    + peer + "' is registered as the user '" + expected + "'";
        }
        return "";
    }
}
