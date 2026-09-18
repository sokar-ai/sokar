package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.vault.SigningKey;

/**
 * One pass over a task's mailbox: everything that has to happen between an agent writing a message
 * and another agent reading it.
 * <p>
 * The order is the design and not a convenience. Nothing is queued before the filter has seen it;
 * nothing is sent before it is queued; what arrived is delivered only after its signature is
 * checked; and the filter's own answers go into the agent's inbox last, around the filter rather
 * than through it.
 * <p>
 * <strong>A step that fails stops the ones after it that depend on it, and no further.</strong> A
 * filter that cannot run means nothing is dispatched or sent - but what already arrived for this
 * task is still delivered, because refusing to hand over somebody else's message is not a sensible
 * answer to our own filter being broken.
 */
public final class MessagePass {

    /**
     * What one pass did, step by step, so a person can see where it stopped.
     *
     * @param taken Messages taken out of the outbox and signed.
     * @param filtered What the filter did, or why it did not run.
     * @param dispatched What was queued, and what was held instead.
     * @param sent Per transport, what was handed over, deferred or refused.
     * @param delivered What reached the agent, and what was held on arrival.
     * @param bounced The filter's answers handed to the agent.
     * @param polled What each transport fetched, and what could not be asked.
     */
    public record Report(List<String> taken, MessageFiltering.Outcome filtered,
            MessageDispatch.Outcome dispatched, Map<String, TransportSend.Result> sent,
            MessageDelivery.Outcome delivered, List<String> bounced,
            TransportPoll.Outcome polled) {
    }

    private final CommandRunner runner;

    private final SigningKey key;

    private final Path filter;

    private final TransportDirectory transports;

    /**
     * Constructor.
     *
     * @param runner How commands are run.
     * @param key The host's signing key.
     * @param filter The message filter, or {@code null} when none is installed.
     * @param transports Where the adapters are.
     */
    public MessagePass(final CommandRunner runner, final SigningKey key, final Path filter,
            final TransportDirectory transports) {
        this.runner = runner;
        this.key = key;
        this.filter = filter;
        this.transports = transports;
    }

    /**
     * Runs one pass.
     *
     * @param mailbox The task's mailbox.
     * @param mail The project's peers, for resolving where a message goes.
     * @param peers Who this task may hear from, with the keys allowed for each.
     * @return What happened.
     * @throws IOException A file could not be read or moved.
     */
    public Report run(final Mailbox mailbox, final Mail mail,
            final List<MessageDelivery.Peer> peers) throws IOException {
        final MessageRecord record = new MessageRecord(mailbox);
        final List<String> taken = new MessageIntake(key).take(mailbox);
        for (final String message : taken) {
            // The role goes into the record: a person writing into a conversation has to be
            // distinguishable from the agent afterwards, and the message itself may be gone by
            // the time anybody asks.
            record.append(MessageRecord.TAKEN, message, "", "",
                    MessageFile.role(mailbox.incoming().resolve(message)));
        }
        final MessageFiltering.Outcome filtered = new MessageFiltering(runner, filter).run(mailbox);

        final MessageBudget budget = new MessageBudget(record, mail);
        MessageDispatch.Outcome dispatched =
                new MessageDispatch.Outcome(Map.of(), List.of(), Map.of());
        final Map<String, TransportSend.Result> sent = new LinkedHashMap<>();
        if (filtered.ran()) {
            dispatched = new MessageDispatch().dispatch(mailbox, mail, new Moderation(mailbox),
                    budget);
            for (final Map.Entry<String, String> queued : dispatched.queued().entrySet()) {
                record.append(MessageRecord.QUEUED, queued.getKey(), "",
                        dispatched.peers().getOrDefault(queued.getKey(), ""), queued.getValue());
            }
            for (final MessageDelivery.Held stuck : dispatched.held()) {
                record.append(MessageRecord.HELD, stuck.message(), "", stuck.reason());
                // The sender is told the moment it is held, not when somebody gets round to the
                // hold list: an agent with no answer waits forever or sends the same thing again.
                new HostBounce().write(mailbox, mailbox.hold().resolve(stuck.message()),
                        "it was not sent: " + stuck.reason(), false);
            }
            final TransportSend send = new TransportSend(runner, transports);
            for (final String transport : queues(mailbox)) {
                final TransportSend.Result result = send.send(mailbox, transport);
                sent.put(transport, result);
                for (final String message : result.sent()) {
                    record.append(MessageRecord.SENT, message, "", transport);
                }
                for (final String message : result.deferred()) {
                    record.append(MessageRecord.DEFERRED, message, "", transport);
                }
                for (final MessageDelivery.Held stuck : result.refused()) {
                    record.append(MessageRecord.HELD, stuck.message(), "", stuck.reason());
                    // A permanent transport failure is final, so the answer says so rather than
                    // leaving the agent to believe its message is still on its way.
                    new HostBounce().write(mailbox, mailbox.hold().resolve(stuck.message()),
                            "it could not be delivered: " + stuck.reason(), true);
                }
            }
        }

        // Asked before anything is delivered: a transport that keeps its arrivals elsewhere has
        // to be given the chance to put them here, or the delivery below would walk past them.
        final TransportPoll.Outcome polled =
                new TransportPoll(runner, transports).poll(mailbox);
        for (final Map.Entry<String, String> failure : polled.failures().entrySet()) {
            record.append(MessageRecord.HELD, "", "", failure.getKey(),
                    "it could not be asked what arrived: " + failure.getValue());
        }

        // Independent of our own filter: what a peer sent is delivered whether or not this machine
        // can send anything today.
        final MessageDelivery.Outcome delivered =
                new MessageDelivery().deliver(mailbox, peers, record.delivered(),
                        new InboundCheck(runner, filter, mail), budget,
                        attestedBy(polled, mail));
        for (final MessageDelivery.Delivered one : delivered.delivered()) {
            record.append(MessageRecord.DELIVERED, one.message(), one.id(), one.peer(), "");
        }
        for (final MessageDelivery.Held stuck : delivered.held()) {
            record.append(MessageRecord.HELD, stuck.message(), "", stuck.reason());
        }
        for (final MessageDelivery.Repeat repeat : delivered.duplicates()) {
            record.append(MessageRecord.DUPLICATE, repeat.message(), repeat.id(),
                    "it had been delivered before");
        }
        final List<String> bounced = new BounceDelivery().deliver(mailbox);
        return new Report(taken, filtered, dispatched, sent, delivered, bounced, polled);
    }

    /**
     * Builds the ownership check for messages this pass fetched.
     * <p>
     * Which transport brought a message decides whether it has to prove who owned it, and that is
     * known only here - the message itself does not say, and a transport's own word about it is the
     * claim that must not be trusted.
     *
     * @param polled What each transport fetched.
     * @param mail The project's peers, where an address names a Unix user.
     * @return The check, which lets through anything nobody promised anything about.
     */
    private MessageDelivery.Attested attestedBy(final TransportPoll.Outcome polled,
            final Mail mail) {
        return (mailbox, message, peer) -> {
            final String carrier = polled.arrivals().get(message.getFileName().toString());
            // Nothing fetched it in this pass means nothing was promised about it - not that
            // nothing is known. An attestation lying beside it is still read, because reading one
            // can only hold a message and never admit one. Measured on the VM on 2026-09-18: with
            // this short-circuited, a message fetched in an earlier pass reached the agent with a
            // contradicting owner file unread beside it.
            final Path adapter = carrier == null ? null : transports.find(carrier);
            final boolean attesting = adapter != null
                    && TransportDescription.of(runner, adapter).attestsOwner();
            return OwnerAttestation.refuse(mailbox, message, peer, mail, attesting);
        };
    }

    private List<String> queues(final Mailbox mailbox) throws IOException {
        final Path root = mailbox.root().resolve("queue");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            final List<String> names = new ArrayList<>(
                    entries.filter(Files::isDirectory)
                            .map(path -> path.getFileName().toString()).toList());
            names.sort(Comparator.naturalOrder());
            return names;
        }
    }
}
