package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.jspecify.annotations.Nullable;

/**
 * What the host does once per pass for every transport that keeps a conversation of its own - a room - before
 * each mailbox is moved along: fetches what arrived in each project's conversation, hands each message to the
 * task it names, and tells the conversation which messages a task's agent has taken.
 * <p>
 * <strong>One poll per project's conversation, not per task</strong>, with the project's secrets from
 * {@code setup}: so everything a poll brings is known to be that project's, and a task's short name in
 * {@code metadata.to} is that project's task - never another project's task of the same name. A poll per task
 * would hand each task every other task's messages. A message for a task on another machine is not this
 * machine's, and is dropped here: the conversation is shared, this machine's inbox is only the part of it
 * that names one of its tasks.
 * <p>
 * <strong>Read is when the agent takes it</strong> (decided 2026-09-29): a message that has moved from the
 * task's {@code inbox/new} to {@code inbox/cur} gets the transport's {@code read} with that task's secrets,
 * once, for a transport that confirms reading.
 */
final class TransportConversations {

    /** Beside a mailbox's record: messages that came through a conversation, one file name per line. */
    static final String ARRIVED = "conversation-arrived";

    /** Beside a mailbox's record: those already marked read, one file name per line. */
    static final String MARKED = "conversation-read";

    /** Beside a mailbox's record: what this task sent that was read where it went, one file name per line. */
    static final String ANSWERED = "conversation-sent-read";

    /** How long what a task sent is asked about: a message nobody read in a week is not waited on. */
    static final java.time.Duration ASKED_FOR = java.time.Duration.ofDays(7);

    /**
     * A task that takes part in a conversation on this machine.
     *
     * @param container The task.
     * @param mailbox Its mailbox.
     * @param project Its project.
     * @param scheme The transport whose conversation it is in.
     */
    record Member(String container, Mailbox mailbox, String project, String scheme) {
    }

    /**
     * What one pass did.
     *
     * @param handed Message file name to the task it was handed to.
     * @param dropped How many were for no task on this machine.
     * @param marked Message file names marked read.
     * @param failures What could not be done.
     */
    record Outcome(Map<String, String> handed, int dropped, List<String> marked, List<String> failures) {
    }

    private final SokarContext context;

    private final TransportDirectory transports;

    private final TransportLifecycle lifecycle;

    /**
     * Constructor.
     *
     * @param context Where the vault, the state and the commands are.
     * @param transports Where the adapters are.
     */
    TransportConversations(SokarContext context, TransportDirectory transports) {
        this.context = context;
        this.transports = transports;
        this.lifecycle = new TransportLifecycle(context, transports);
    }

    /**
     * Returns what a transport needs to act as a task: that task's secrets, and the project's conversation as
     * the destination.
     *
     * @param scheme The transport.
     * @param project The task's project.
     * @param container The task.
     * @return What it is given, or {@code null} when the task holds nothing for this transport.
     */
    TransportSend.@Nullable Acting acting(String scheme, String project, String container) {
        final Map<String, String> secrets = lifecycle.secrets(scheme, "task/" + container);
        final TransportLifecycle.Conversation conversation = lifecycle.conversation(scheme, project);
        if (secrets.isEmpty() || conversation == null) {
            return null;
        }
        return new TransportSend.Acting(secrets, conversation.conversation());
    }

    /**
     * Runs one pass: polls each project's conversation, hands out what arrived, marks what was taken.
     *
     * @param members The tasks on this machine that take part in a conversation.
     * @return What happened.
     */
    Outcome pass(List<Member> members) {
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> marked = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        int dropped = 0;
        final Map<String, List<Member>> byConversation = new LinkedHashMap<>();
        members.forEach(member -> byConversation.computeIfAbsent(member.scheme() + "\n" + member.project(),
                key -> new ArrayList<>()).add(member));
        for (final List<Member> group : byConversation.values()) {
            final String scheme = group.getFirst().scheme();
            final String project = group.getFirst().project();
            final Path adapter = transports.find(scheme);
            final Map<String, String> secrets = lifecycle.secrets(scheme, "project/" + project);
            if (adapter == null || secrets.isEmpty()) {
                // Not installed, or never set up: the task's start says so; nothing to poll with here.
                continue;
            }
            try {
                final Path inbound = context.paths().xdg().state().resolve("transport").resolve(scheme)
                        .resolve("inbound").resolve(project);
                Files.createDirectories(inbound);
                final CommandResult polled = context.runner().run(Command.of(adapter.toString(), "poll", "--into",
                        inbound.toString()).withEnvironment(secrets));
                if (!polled.successful()) {
                    failures.add("the " + scheme + " transport could not be asked what arrived for " + project
                            + " (exit " + polled.exitCode() + "): " + polled.standardError().strip());
                }
                dropped += handOut(inbound, group, handed);
                if ("read".equals(TransportDescription.of(context.runner(), adapter).confirms())) {
                    for (final Member member : group) {
                        marked.addAll(markRead(adapter, member, failures));
                        askRead(adapter, member, failures);
                    }
                }
            } catch (IOException ex) {
                failures.add(String.valueOf(ex.getMessage()));
            }
        }
        return new Outcome(handed, dropped, marked, failures);
    }

    /**
     * Hands each message that arrived to the task its {@code metadata.to} names, with its signature.
     *
     * @param inbound Where the transport put them.
     * @param members The project's tasks on this machine.
     * @param handed Filled with message to task.
     * @return How many were for no task here, and were dropped.
     * @throws IOException If a file cannot be moved.
     */
    static int handOut(Path inbound, List<Member> members, Map<String, String> handed) throws IOException {
        // By the name a peer of the project uses - the task's short name - and by its container's name.
        final Map<String, Member> byName = new LinkedHashMap<>();
        members.forEach(member -> {
            byName.put(member.container(), member);
            final String task = org.fuin.sokar.runtime.ContainerName.taskIn(member.project(), member.container());
            if (task != null) {
                byName.putIfAbsent(task, member);
            }
        });
        int dropped = 0;
        for (final Path message : messages(inbound)) {
            final String name = message.getFileName().toString();
            final Path signature = message.resolveSibling(name + ".sig");
            final String recipient = recipient(message);
            final Member to = recipient == null ? null : byName.get(recipient);
            if (to == null) {
                Files.deleteIfExists(message);
                Files.deleteIfExists(signature);
                dropped++;
                continue;
            }
            Files.createDirectories(to.mailbox().inbound());
            if (Files.isRegularFile(signature)) {
                Files.move(signature, to.mailbox().inbound().resolve(name + ".sig"), StandardCopyOption.REPLACE_EXISTING);
            }
            // The message last, so the task's pass never sees a message whose signature is still on its way.
            Files.move(message, to.mailbox().inbound().resolve(name), StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(to.mailbox().record());
            Files.writeString(to.mailbox().record().resolve(ARRIVED), name + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            handed.put(name, to.container());
        }
        return dropped;
    }

    private List<String> markRead(Path adapter, Member member, List<String> failures) throws IOException {
        final Set<String> taken = taken(member.mailbox());
        if (taken.isEmpty()) {
            return List.of();
        }
        final Map<String, String> secrets = lifecycle.secrets(member.scheme(), "task/" + member.container());
        if (secrets.isEmpty()) {
            return List.of();
        }
        final List<String> marked = new ArrayList<>();
        for (final String name : taken) {
            final CommandResult result = context.runner().run(Command.of(adapter.toString(), "read", reference(name))
                    .withEnvironment(secrets));
            if (result.successful() || result.exitCode() == 77) {
                // 77: the conversation holds the message no more - nothing is left to mark, and asking again
                // every pass would not change that.
                Files.writeString(member.mailbox().record().resolve(MARKED), name + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                marked.add(name);
            } else if (result.exitCode() != TransportSend.TEMPORARY) {
                failures.add("a message to " + member.container() + " could not be marked read (exit "
                        + result.exitCode() + "): " + result.standardError().strip());
            }
        }
        return marked;
    }

    /**
     * Asks, for what this task sent through the conversation in the last week and nobody has read yet,
     * whether it has been read where it went - {@code receipt <reference> --by <address>} - and records it as
     * read when it has. The address is the recipient's as the transport named it: a task's from {@code enroll},
     * a person's from {@code join}. One the transport never named is not asked about.
     *
     * @param adapter The transport.
     * @param member The task that sent.
     * @param failures Filled with what could not be asked.
     * @throws IOException If a record cannot be read or written.
     */
    private void askRead(Path adapter, Member member, List<String> failures) throws IOException {
        final Path sent = member.mailbox().sent();
        if (!Files.isDirectory(sent)) {
            return;
        }
        final Set<String> answered = lines(member.mailbox().record().resolve(ANSWERED));
        final Map<String, String> tasks = lifecycle.addresses(member.scheme(), member.project());
        final Map<String, String> people = lifecycle.people(member.scheme(), member.project());
        final Map<String, String> secrets = lifecycle.secrets(member.scheme(), "task/" + member.container());
        if (secrets.isEmpty() || tasks.isEmpty() && people.isEmpty()) {
            return;
        }
        final java.time.Instant since = java.time.Instant.now().minus(ASKED_FOR);
        final List<Path> receipts;
        try (Stream<Path> listed = Files.list(sent)) {
            receipts = listed.filter(path -> path.getFileName().toString().endsWith(".receipt.json")).sorted().toList();
        }
        for (final Path receipt : receipts) {
            final String name = receipt.getFileName().toString().replace(".receipt.json", "");
            final Path message = sent.resolve(name);
            if (answered.contains(name) || !Files.isRegularFile(message)
                    || Files.getLastModifiedTime(receipt).toInstant().isBefore(since)) {
                continue;
            }
            final String reference = text(receipt, null, "reference");
            final String to = recipient(message);
            final String address = to == null ? null : addressOf(to, member.project(), tasks, people);
            if (reference == null || address == null || to == null) {
                continue;
            }
            final CommandResult result = context.runner().run(Command.of(adapter.toString(), "receipt", reference,
                    "--by", address).withEnvironment(secrets));
            if (!result.successful()) {
                if (result.exitCode() != TransportSend.TEMPORARY) {
                    failures.add("whether " + to + " read a message from " + member.container()
                            + " could not be asked (exit " + result.exitCode() + "): " + result.standardError().strip());
                }
                continue;
            }
            final Object answer;
            try {
                answer = org.fuin.sokar.wire.Json.parse(result.standardOutput());
            } catch (RuntimeException ex) {
                continue;
            }
            if (answer instanceof Map<?, ?> said && "read".equals(said.get("state"))) {
                new MessageRecord(member.mailbox()).append(MessageRecord.READ, name, MessageFile.id(message), to,
                        said.get("at") instanceof String at ? "read at " + at : "", MessageRecord.OUT);
                Files.writeString(member.mailbox().record().resolve(ANSWERED), name + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }
    }

    /**
     * Returns the address a peer name stands for in a project's conversation.
     *
     * @param to The name a message addressed.
     * @param project The project.
     * @param tasks Task container to address.
     * @param people Person to address.
     * @return The address, or {@code null} when the transport named none for it.
     */
    static @Nullable String addressOf(String to, String project, Map<String, String> tasks, Map<String, String> people) {
        final String task = tasks.containsKey(to) ? tasks.get(to) : tasks.get("sokar-" + project + "-" + to);
        return task != null ? task : people.get(to);
    }

    private static @Nullable String text(Path file, @Nullable String section, String key) {
        try {
            if (org.fuin.sokar.wire.Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                    instanceof Map<?, ?> document && document.get(key) instanceof String value) {
                return value;
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable: nothing to ask about.
        }
        return null;
    }

    /**
     * Returns the messages that came through a conversation which this task's agent has taken - moved into
     * {@code inbox/cur} - and that are not yet marked read.
     *
     * @param mailbox The task's mailbox.
     * @return Their file names.
     * @throws IOException If a record cannot be read.
     */
    static Set<String> taken(Mailbox mailbox) throws IOException {
        final Set<String> arrived = lines(mailbox.record().resolve(ARRIVED));
        if (arrived.isEmpty() || !Files.isDirectory(mailbox.inboxCur())) {
            return Set.of();
        }
        arrived.removeAll(lines(mailbox.record().resolve(MARKED)));
        final Set<String> taken = new LinkedHashSet<>();
        try (Stream<Path> current = Files.list(mailbox.inboxCur())) {
            current.map(path -> path.getFileName().toString())
                    // A maildir reader may append flags to the name it moves into cur ("<name>:2,S").
                    .map(name -> name.contains(":") ? name.substring(0, name.indexOf(':')) : name)
                    .filter(arrived::contains).forEach(taken::add);
        }
        return taken;
    }

    /**
     * Returns the reference a message's file name stands for: the name {@code poll} gave it, less
     * {@code .json}.
     *
     * @param name The file name.
     * @return The reference.
     */
    static String reference(String name) {
        return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
    }

    private static @Nullable String recipient(Path message) {
        try {
            if (org.fuin.sokar.wire.Json.parse(Files.readString(message, StandardCharsets.UTF_8))
                    instanceof Map<?, ?> document && document.get("metadata") instanceof Map<?, ?> metadata
                    && metadata.get("to") instanceof String to) {
                return to;
            }
        } catch (IOException | RuntimeException ex) {
            // Not a message this can read: it names nobody here.
        }
        return null;
    }

    private static List<Path> messages(Path inbound) throws IOException {
        try (Stream<Path> entries = Files.list(inbound)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Set<String> lines(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashSet<>();
        }
        final Set<String> lines = new LinkedHashSet<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        return lines;
    }
}
