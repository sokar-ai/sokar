package org.fuin.sokar.app;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.fuin.sokar.core.project.Mail;

/**
 * How much one task and one peer may say to each other in a day.
 * <p>
 * <strong>In both directions, and the same number both ways.</strong> The work a message causes is
 * paid for by whoever receives it, so a limit that only counted what left would leave a task
 * defenceless against a peer that talks too much - and a limit that only counted what arrived would
 * let this machine be the one that talks too much.
 * <p>
 * <strong>Counted from the record</strong>, not from a counter kept beside it. A counter is a second
 * thing that can disagree with what happened; the record is written by the thing that happened.
 * <p>
 * Beyond the limit a message is <em>held with a reason</em>, never dropped and never queued. An
 * agent that is told it is over its budget can wait or say something shorter; one whose messages
 * vanish learns nothing.
 */
public final class MessageBudget {

    /** How far back a day counts. Rolling, so nothing is released by midnight arriving. */
    public static final Duration DAY = Duration.ofDays(1);

    private final MessageRecord record;

    private final Mail mail;

    /**
     * Constructor.
     *
     * @param record The task's record, which is where the count comes from.
     * @param mail The project's peers, which is where the limits are declared.
     */
    public MessageBudget(final MessageRecord record, final Mail mail) {
        this.record = record;
        this.mail = mail;
    }

    /**
     * Says whether one more message may go out to a peer.
     *
     * @param peer The peer.
     * @return An empty string when it may, otherwise why it may not.
     * @throws IOException Reading the record failed.
     */
    public String outbound(final String peer) throws IOException {
        return outbound(peer, 0);
    }

    /**
     * Says whether one more message may go out to a peer, counting this pass as well.
     *
     * @param peer The peer.
     * @param thisPass How many have already gone to it in the pass now running, which the record
     *        does not know yet because it is written when the pass ends.
     * @return An empty string when it may, otherwise why it may not.
     * @throws IOException Reading the record failed.
     */
    public String outbound(final String peer, final int thisPass) throws IOException {
        return beyond(peer, MessageRecord.QUEUED, "sent to", thisPass);
    }

    /**
     * Says whether one more message may be handed to the agent from a peer.
     *
     * @param peer The peer.
     * @return An empty string when it may, otherwise why it may not.
     * @throws IOException Reading the record failed.
     */
    public String inbound(final String peer) throws IOException {
        return inbound(peer, 0);
    }

    /**
     * Says whether one more message may reach the agent, counting this pass as well.
     *
     * @param peer The peer.
     * @param thisPass How many have already been handed over in the pass now running.
     * @return An empty string when it may, otherwise why it may not.
     * @throws IOException Reading the record failed.
     */
    public String inbound(final String peer, final int thisPass) throws IOException {
        return beyond(peer, MessageRecord.DELIVERED, "accepted from", thisPass);
    }

    /**
     * Says how many messages went out to a peer in the last day - what {@link #outbound} counts.
     *
     * @param peer The peer.
     * @return The count.
     * @throws IOException Reading the record failed.
     */
    public int sentToday(final String peer) throws IOException {
        return record.count(MessageRecord.QUEUED, peer, Instant.now().minus(DAY));
    }

    /**
     * Says how many messages from a peer reached the agent in the last day - what {@link #inbound} counts.
     *
     * @param peer The peer.
     * @return The count.
     * @throws IOException Reading the record failed.
     */
    public int receivedToday(final String peer) throws IOException {
        return record.count(MessageRecord.DELIVERED, peer, Instant.now().minus(DAY));
    }

    private String beyond(final String peer, final String event, final String direction,
            final int thisPass) throws IOException {
        final Mail.Peer known = mail.peer(peer);
        if (known == null) {
            // Not this step's refusal to make: a peer the project does not list is turned away
            // where that is decided, with a message that says so.
            return "";
        }
        // The pass now running is counted too. The record is written when a pass ends, so a
        // budget that read only the record would let a single pass through with any number of
        // messages - which is exactly the runaway the budget exists to stop.
        final int used = record.count(event, peer, Instant.now().minus(DAY)) + thisPass;
        if (used < known.perDay()) {
            return "";
        }
        return "the last day already " + direction + " " + peer + " " + used + " message(s), which"
                + " is what this project allows";
    }
}
