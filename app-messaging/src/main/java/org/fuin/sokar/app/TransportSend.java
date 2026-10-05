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
    public record Acting(java.util.Map<String, String> environment, @org.jspecify.annotations.Nullable String to,
            java.util.Map<String, String> accounts) {

        /**
         * Constructor for a transport that is told whom to mention by nobody.
         *
         * @param environment Variables for the transport.
         * @param to The destination to hand the transport instead of the peer's, or {@code null}.
         */
        public Acting(java.util.Map<String, String> environment, @org.jspecify.annotations.Nullable String to) {
            this(environment, to, java.util.Map.of());
        }
    }

    /** The option a transport announces, as {@code "mention": true}, when it mentions an account it is given. */
    static final String MENTION = "mention";

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
        final TransportDescription described = TransportDescription.of(runner, adapter);
        final boolean shows = described.takes(SHOWN);
        final boolean mentions = described.takes(MENTION);
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
            // The room the task acts in, as every task of the project is reached through it - and a person's own chat
            // only for an answer to what they said there; in the room a person is mentioned instead (the operator in
            // walk 10, 2026-10-04).
            final String queued = Files.readString(destination, StandardCharsets.UTF_8).strip();
            final boolean person = queued.startsWith("@") && acting != null && acting.to() != null;
            final boolean direct = person && "direct".equals(via(message));
            final List<String> arguments = new ArrayList<>(List.of(adapter.toString(), "send", message.toString(),
                    signature.toString(), "--to", direct || (!person && !queued.isEmpty()) || acting == null
                            || acting.to() == null ? queued : acting.to()));
            // Beside the message, never inside it, and only for a transport that says it takes it: an option it does
            // not list is its 64. Written here, on the host, from the checked message - never a file a task wrote.
            final Path line = message.resolveSibling(name + SHOWN_SUFFIX);
            if (shows) {
                Files.writeString(line, shown(Files.readString(message, StandardCharsets.UTF_8), task(mailbox)),
                        StandardCharsets.UTF_8);
                arguments.add("--shown");
                arguments.add(line.toString());
            }
            // The task it is for, mentioned by its account, so the room shows whom it is meant for: never a person, who is
            // reached in a direct chat, and nobody for the room's people.
            final String mention = !mentions || acting == null || direct ? null
                    : person ? queued : acting.accounts().get(recipient(message));
            if (mention != null) {
                arguments.add("--" + MENTION);
                arguments.add(mention);
            }
            Command command = Command.of(arguments);
            if (acting != null) {
                command = command.withEnvironment(acting.environment());
            }
            final CommandResult result;
            try {
                result = runner.run(HelperEnvironment.chosen(command));
            } finally {
                Files.deleteIfExists(line);
            }
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

    /**
     * Returns the line a person reads in a conversation: who wrote to whom, and the text.
     * <p>
     * Made on the host from the message the filter checked, mechanically and from nothing else: its text parts, its
     * {@code metadata.to}, and the task it came from as the host knows it. No kind, no id, no word Sokar adds. A part that is not text is named
     * by its media type, never its bytes. The transport carries it unread beside the message, which travels whole for
     * the agents.
     *
     * @param message The message as JSON.
     * @param from The sending task's name, as the host knows it.
     * @return The line, or "" when the message cannot be read.
     */
    private static String via(final Path message) {
        try {
            if (org.fuin.sokar.wire.Json.parse(Files.readString(message, StandardCharsets.UTF_8))
                    instanceof java.util.Map<?, ?> document && document.get("metadata") instanceof java.util.Map<?, ?> m
                    && m.get("via") instanceof String via) {
                return via;
            }
        } catch (final IOException | RuntimeException ex) {
            // Unreadable here: the room.
        }
        return "";
    }

    private static String recipient(final Path message) {
        try {
            if (org.fuin.sokar.wire.Json.parse(Files.readString(message, StandardCharsets.UTF_8))
                    instanceof java.util.Map<?, ?> document && document.get("metadata") instanceof java.util.Map<?, ?> m
                    && m.get("to") instanceof String to) {
                return to;
            }
        } catch (final IOException | RuntimeException ex) {
            // Unreadable here: nobody is mentioned.
        }
        return "";
    }

    static String shown(final String message, final String from) {
        if (!(org.fuin.sokar.wire.Json.parse(message) instanceof java.util.Map<?, ?> document)) {
            return "";
        }
        final java.util.Map<?, ?> metadata = document.get("metadata") instanceof java.util.Map<?, ?> said ? said
                : java.util.Map.of();
        final List<String> parts = new ArrayList<>();
        if (document.get("parts") instanceof List<?> all) {
            for (final Object part : all) {
                if (part instanceof java.util.Map<?, ?> each && each.get("text") instanceof String text) {
                    parts.add(text);
                } else if (part instanceof java.util.Map<?, ?> each) {
                    parts.add("[" + (each.get("mediaType") instanceof String type ? type : "data") + "]");
                }
            }
        }
        return String.join("\n\n", parts);
    }

    /** The option a transport announces, as {@code "shown": true}, when it shows a person a line of Sokar's. */
    static final String SHOWN = "shown";

    /** The line's file beside a queued message, there only while its transport runs. */
    private static final String SHOWN_SUFFIX = ".shown";

    private static String task(final Mailbox mailbox) {
        return mailbox.root().getFileName().toString();
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
