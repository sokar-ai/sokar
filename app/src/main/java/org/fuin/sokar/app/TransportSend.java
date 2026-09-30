package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;

/**
 * Hands queued messages to the transport that carries them.
 * <p>
 * The adapter is asked and believed about one thing only: whether it handed the message over, and
 * whether a failure is worth trying again. Everything else stays here - moving the message, writing
 * the receipt, deciding what a permanent failure means.
 * <p>
 * <strong>The three exits are the contract</strong>, and they are the reason a queue can be read by
 * a person: {@code 0} handed over, {@code 75} temporary and therefore still ours to retry, anything
 * else permanent. Getting those backwards either loses a message or retries one forever.
 */
public final class TransportSend {

    /** What an adapter exits with when it could not deliver now, but might later. */
    public static final int TEMPORARY = 75;

    /**
     * What one pass did.
     *
     * @param sent Messages a transport took.
     * @param deferred Messages still waiting, which the next pass tries again.
     * @param refused Messages nothing will carry, with why.
     */
    public record Result(List<String> sent, List<String> deferred,
            List<MessageDelivery.Held> refused) {
    }

    private final CommandRunner runner;

    private final TransportDirectory transports;

    private final Access access;

    /**
     * Constructor.
     *
     * @param runner How commands are run.
     * @param transports Where the adapters are.
     */
    public TransportSend(final CommandRunner runner, final TransportDirectory transports) {
        this(runner, transports, (transport, container) -> null);
    }

    /**
     * Constructor for transports that act as the sending task.
     *
     * @param runner How commands are run.
     * @param transports Where the adapters are.
     * @param access What a transport needs to act as a task, beyond the message.
     */
    public TransportSend(final CommandRunner runner, final TransportDirectory transports, final Access access) {
        this.runner = runner;
        this.transports = transports;
        this.access = access;
    }

    /**
     * What a transport is given to act as the task that sends: its environment - a token, never an
     * argument - and, where the host decides it, the destination.
     *
     * @param environment Variables for the transport.
     * @param to The destination to hand the transport instead of the peer's, or {@code null} for the peer's.
     */
    public record Acting(java.util.Map<String, String> environment, @org.jspecify.annotations.Nullable String to) {
    }

    /** Says what a transport needs to act as a task; {@code null} for nothing beyond the message. */
    @FunctionalInterface
    public interface Access {

        /**
         * Returns what a transport needs to act as a task.
         *
         * @param transport The transport.
         * @param container The sending task.
         * @return What it is given, or {@code null} for nothing.
         * @throws IOException If it cannot be had; the message is deferred.
         */
        @org.jspecify.annotations.Nullable Acting acting(String transport, String container) throws IOException;
    }

    /**
     * Sends what is queued for one transport, and retries what was deferred before it.
     *
     * @param mailbox The task's mailbox.
     * @param transport Transport name.
     * @return What was sent, deferred and refused.
     * @throws IOException Moving a message failed.
     */
    public Result send(final Mailbox mailbox, final String transport) throws IOException {
        final List<String> sent = new ArrayList<>();
        final List<String> deferred = new ArrayList<>();
        final List<MessageDelivery.Held> refused = new ArrayList<>();
        final Path adapter = transports.find(transport);
        final Path active = mailbox.queueActive(transport);
        final Path waiting = mailbox.queueDeferred(transport);
        if (adapter == null) {
            // Not a refusal: the message stays where it is. A machine without the adapter installed
            // has not lost anything, and installing it later is what makes the queue move.
            for (final Path message : queued(active)) {
                deferred.add(message.getFileName().toString());
                moveWithCompanions(message, waiting);
            }
            return new Result(sent, deferred, refused);
        }
        final Acting acting;
        try {
            acting = access.acting(transport, mailbox.root().getFileName().toString());
        } catch (final IOException ex) {
            // Its account or its room cannot be had now: nothing is lost, the queue waits for the next pass.
            for (final Path message : queued(active)) {
                deferred.add(message.getFileName().toString());
                moveWithCompanions(message, waiting);
            }
            return new Result(sent, deferred, refused);
        }
        // What was deferred is tried first, so a queue drains in the order it filled.
        for (final Path message : concat(queued(waiting), queued(active))) {
            final String name = message.getFileName().toString();
            final Path signature = message.resolveSibling(name + ".sig");
            final Path destination =
                    message.resolveSibling(name + MessageDispatch.DESTINATION_SUFFIX);
            if (!Files.isRegularFile(destination)) {
                moveWithCompanions(message, mailbox.hold());
                refused.add(new MessageDelivery.Held(name,
                        "it is queued without a destination, so nothing knows where it was going"));
                continue;
            }
            Command command = Command.of(adapter.toString(), "send", message.toString(), signature.toString(),
                    "--to", acting != null && acting.to() != null ? acting.to()
                            : Files.readString(destination, StandardCharsets.UTF_8).strip());
            if (acting != null) {
                command = command.withEnvironment(acting.environment());
            }
            final CommandResult result = runner.run(command);
            if (result.successful()) {
                final Path receipt = mailbox.sent().resolve(name + ".receipt.json");
                Files.writeString(receipt, result.standardOutput(), StandardCharsets.UTF_8);
                moveWithCompanions(message, mailbox.sent());
                sent.add(name);
            } else if (result.exitCode() == TEMPORARY) {
                if (!waiting.equals(message.getParent())) {
                    moveWithCompanions(message, waiting);
                }
                deferred.add(name);
            } else {
                moveWithCompanions(message, mailbox.hold());
                refused.add(new MessageDelivery.Held(name, result.standardError().strip().isEmpty()
                        ? "the transport refused it, exit " + result.exitCode()
                        : result.standardError().strip()));
            }
        }
        return new Result(sent, deferred, refused);
    }

    private List<Path> concat(final List<Path> first, final List<Path> second) {
        final List<Path> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private void moveWithCompanions(final Path message, final Path target) throws IOException {
        Files.createDirectories(target);
        final String name = message.getFileName().toString();
        for (final String suffix : new String[] {".sig", MessageDispatch.DESTINATION_SUFFIX}) {
            final Path companion = message.resolveSibling(name + suffix);
            if (Files.isRegularFile(companion)) {
                Files.move(companion, target.resolve(name + suffix),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(message, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private List<Path> queued(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }
}
