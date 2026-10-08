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

    /** Beside a mailbox's record: those the transport refused to mark read, never asked about again. */
    static final String MARK_REFUSED = "conversation-read-refused";

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
    TransportSend.@Nullable Acting acting(String scheme, String project, String container) throws java.io.IOException {
        final Map<String, String> secrets = lifecycle.secrets(scheme, "task/" + container);
        final TransportLifecycle.Conversation conversation = lifecycle.conversation(scheme, project);
        if (conversation == null) {
            return null;
        }
        if (secrets.isEmpty()) {
            // Its project's conversation is there and its account is not: run without it, the transport refused, and
            // the message came back held with the transport's words about a variable. It waits instead, and goes
            // once the account can be read - an unlocked vault - or the task is taken in by starting it again.
            throw new java.io.IOException("this task has no account in the project's " + scheme + " conversation that"
                    + " can be read here: the vault is locked, or the task was never taken in");
        }
        // Each task of the project by its name and its short name, to its account: whom a message to a task mentions.
        final Map<String, String> accounts = new LinkedHashMap<>();
        lifecycle.addresses(scheme, project).forEach((task, account) -> {
            accounts.put(task, account);
            final String shortName = org.fuin.sokar.runtime.ContainerName.taskIn(project, task);
            if (shortName != null) {
                accounts.putIfAbsent(shortName, account);
            }
        });
        return new TransportSend.Acting(secrets, conversation.conversation(), accounts);
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
                final TransportDescription described = TransportDescription.of(context.runner(), adapter);
                // Where a waiting poll runs for this conversation, it fetches; here only what it left is handed out.
                if (!WAITING.contains(key(scheme, project))) {
                    poll(adapter, described, secrets, inbound, scheme, project, 0, failures);
                }
                final Map<String, String> accounts = lifecycle.addresses(scheme, project);
                final Map<String, String> people = lifecycle.people(scheme, project);
                dropped += handOut(inbound, group, accounts, people, handed, failures);
                if (described.takes(DIRECT)) {
                    for (final Member member : group) {
                        if (!WAITING.contains(key(member.scheme(), member.container()))) {
                            dropped += direct(adapter, member, accounts, people, handed, failures, 0);
                        }
                    }
                }
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
     * Marks what the tasks took as read, and asks what the people read, for a transport that confirms by reading: right
     * after a delivery, so the person sees it read then and not at the next timed pass.
     *
     * @param members The tasks something was delivered to.
     * @return What could not be done.
     */
    List<String> confirm(final List<Member> members) {
        final List<String> failures = new ArrayList<>();
        for (final Member member : members) {
            final Path adapter = transports.find(member.scheme());
            if (adapter == null || !"read".equals(TransportDescription.of(context.runner(), adapter).confirms())) {
                continue;
            }
            try {
                markRead(adapter, member, failures);
                askRead(adapter, member, failures);
            } catch (IOException ex) {
                failures.add(String.valueOf(ex.getMessage()));
            }
        }
        return failures;
    }

    /**
     * Hands each message that arrived to every task of the project here, with what came beside it.
     *
     * @param inbound Where the transport put them.
     * @param members The project's tasks on this machine.
     * @param handed Filled with message to task.
     * @param said Filled with a sentence for each message dropped, and why: never dropped without a word.
     * @return How many were for no task here, and were dropped.
     * @throws IOException If a file cannot be moved.
     */
    static int handOut(Path inbound, List<Member> members, Map<String, String> handed, List<String> said)
            throws IOException {
        return handOut(inbound, members, Map.of(), Map.of(), handed, said);
    }

    /** What a transport announces when its poll hands over a person's words in the room. */
    static final String PERSONS = "persons";

    /** The conversations, and the tasks' direct chats, whose polls wait on the server now, by {@link #key}. */
    static final Set<String> WAITING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** How long a waiting poll holds the server's answer open, at most. */
    static final int WAIT_SECONDS = 30;

    static String key(final String scheme, final String name) {
        return scheme + "\n" + name;
    }

    /**
     * Asks a conversation's transport once what arrived, as its relay.
     *
     * @param wait How long the server may hold the answer open for something to arrive; 0 to answer at once.
     */
    private void poll(final Path adapter, final TransportDescription described, final Map<String, String> secrets,
            final Path inbound, final String scheme, final String project, final int wait, final List<String> failures) {
        // A person's words in the room as well, from a transport that says it hands them over.
        final List<String> poll = new ArrayList<>(List.of(adapter.toString(), "poll", "--into", inbound.toString()));
        if (described.takes(PERSONS)) {
            poll.add("--" + PERSONS);
        }
        if (wait > 0) {
            poll.addAll(List.of("--wait", String.valueOf(wait)));
        }
        final CommandResult polled = context.runner().run(HelperEnvironment.chosen(Command.of(poll)
                .withEnvironment(secrets)));
        said(polled, "what arrived for " + project, scheme, failures);
    }

    /**
     * Says what a poll said: its failure, and what it skipped and why even when it succeeded, so a message a
     * transport would not hand over is never lost without a word (walk 10, 2026-10-04).
     */
    private static void said(final CommandResult polled, final String what, final String scheme,
            final List<String> failures) {
        if (!polled.successful()) {
            failures.add("the " + scheme + " transport could not be asked " + what + " (exit " + polled.exitCode()
                    + "): " + polled.standardError().strip());
        } else if (!polled.standardError().isBlank()) {
            failures.add("the " + scheme + " transport, asked " + what + ": " + polled.standardError().strip());
        }
    }

    /**
     * Fetches a conversation and its tasks' direct chats once, each poll waiting on the server up to
     * {@link #WAIT_SECONDS} for something to arrive, and hands out what came - for a loop that keeps a person's word
     * seconds from the task rather than a pass's minute.
     *
     * @param group The project's tasks on this machine that take part in it.
     * @return What happened.
     */
    Outcome waitOnce(final List<Member> group) {
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> failures = new ArrayList<>();
        if (group.isEmpty()) {
            return new Outcome(handed, 0, List.of(), failures);
        }
        final String scheme = group.getFirst().scheme();
        final String project = group.getFirst().project();
        final Path adapter = transports.find(scheme);
        final Map<String, String> secrets = lifecycle.secrets(scheme, "project/" + project);
        int dropped = 0;
        if (adapter != null && !secrets.isEmpty()) {
            try {
                final TransportDescription described = TransportDescription.of(context.runner(), adapter);
                final Path inbound = context.paths().xdg().state().resolve("transport").resolve(scheme)
                        .resolve("inbound").resolve(project);
                Files.createDirectories(inbound);
                poll(adapter, described, secrets, inbound, scheme, project, WAIT_SECONDS, failures);
                final Map<String, String> accounts = lifecycle.addresses(scheme, project);
                final Map<String, String> people = lifecycle.people(scheme, project);
                dropped += handOut(inbound, group, accounts, people, handed, failures);
            } catch (IOException ex) {
                failures.add(String.valueOf(ex.getMessage()));
            }
        }
        return new Outcome(handed, dropped, List.of(), failures);
    }

    /**
     * Fetches what people said to one task in its direct chats, waiting on the server for it, and hands it to that
     * task: a loop of its own per task, so no other poll of its account runs beside it (Agent Matrix, 2026-10-04).
     *
     * @param member The task.
     * @return What happened.
     */
    Outcome waitDirect(final Member member) {
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> failures = new ArrayList<>();
        final Path adapter = transports.find(member.scheme());
        if (adapter != null) {
            try {
                direct(adapter, member, lifecycle.addresses(member.scheme(), member.project()),
                        lifecycle.people(member.scheme(), member.project()), handed, failures, WAIT_SECONDS);
            } catch (IOException ex) {
                failures.add(String.valueOf(ex.getMessage()));
            }
        }
        return new Outcome(handed, 0, List.of(), failures);
    }

    /** What a transport announces when its poll can wait on the server for something to arrive. */
    static final String WAITS = "waits";

    /** What a transport announces when it reads a task's direct chats and sends into them. */
    static final String DIRECT = "direct";

    /**
     * Fetches what people said to one task in its direct chats, with the task's own account, and hands it to that
     * task alone.
     */
    private int direct(final Path adapter, final Member member, final Map<String, String> accounts,
            final Map<String, String> people, final Map<String, String> handed, final List<String> failures,
            final int wait) throws IOException {
        final Map<String, String> secrets = lifecycle.secrets(member.scheme(), "task/" + member.container());
        if (secrets.isEmpty()) {
            return 0;
        }
        final Path into = context.paths().xdg().state().resolve("transport").resolve(member.scheme())
                .resolve("direct").resolve(member.container());
        Files.createDirectories(into);
        final List<String> poll = new ArrayList<>(List.of(adapter.toString(), "poll", "--into", into.toString(),
                "--" + DIRECT));
        if (wait > 0) {
            poll.addAll(List.of("--wait", String.valueOf(wait)));
        }
        final CommandResult polled = context.runner().run(HelperEnvironment.chosen(Command.of(poll)
                .withEnvironment(secrets)));
        said(polled, "what was said to " + member.container() + " directly", member.scheme(), failures);
        return handOut(into, List.of(member), accounts, people, handed, failures);
    }

    /** A person's words as a transport hands them over: {@code <time>--<transport>-person-<id>.json}, unsigned. */
    static final java.util.regex.Pattern PERSON = java.util.regex.Pattern.compile(".*--[a-z0-9-]+-person-[^/]*\\.json");

    /** Beside a message a transport handed over: the account that posted it in the conversation. */
    static final String SENDER_SUFFIX = ".sender";

    /**
     * Hands each message that arrived to every task of the project here that is to read it.
     * <p>
     * <strong>Every agent reads every message in the room</strong> (the operator, 2026-10-04): a task's message
     * goes to every other task, whomever its {@code metadata.to} names, and never back to the task that posted it;
     * {@code metadata.to} says whom it is meant for, not who reads it. A person's words in the room go to every
     * task, and in a direct chat to the one task it is with. They are a person's, so they are not filtered (the
     * operator, 2026-10-04) - and taken only from somebody who joined the project's conversation.
     *
     * @param inbound Where the transport put them.
     * @param members The project's tasks on this machine.
     * @param accounts Each task's account in the conversation, as {@code enroll} named it.
     * @param people Each person who joined, to their account.
     * @param handed Filled with message to the tasks it was handed to.
     * @param said Filled with a sentence for each message dropped, and why: never dropped without a word.
     * @return How many reached no task here.
     * @throws IOException If a file cannot be written or moved.
     */
    static int handOut(Path inbound, List<Member> members, Map<String, String> accounts, Map<String, String> people,
            Map<String, String> handed, List<String> said) throws IOException {
        final String project = members.isEmpty() ? "this project" : "'" + members.getFirst().project() + "'";
        int dropped = 0;
        for (final Path message : messages(inbound)) {
            final String name = message.getFileName().toString();
            final Path signature = message.resolveSibling(name + ".sig");
            final Path sender = message.resolveSibling(name + SENDER_SUFFIX);
            final List<Member> readers;
            final @Nullable String person;
            final byte @Nullable [] bytes;
            if (!Files.isRegularFile(signature) && PERSON.matcher(name).matches()) {
                final PersonsWords words = PersonsWords.read(message, members, accounts, people);
                if (words.refused() != null) {
                    said.add("dropped " + name + ": " + words.refused());
                    Files.deleteIfExists(message);
                    dropped++;
                    continue;
                }
                readers = words.readers();
                person = words.person();
                bytes = words.message();
            } else {
                final String posted = Files.isRegularFile(sender)
                        ? Files.readString(sender, StandardCharsets.UTF_8).strip() : "";
                readers = members.stream().filter(member -> posted.isEmpty()
                        || !posted.equals(accounts.get(member.container()))).toList();
                person = null;
                bytes = null;
            }
            if (readers.isEmpty()) {
                said.add("dropped " + name + ": no task of " + project + " on this machine is to read it");
            }
            for (final Member to : readers) {
                Files.createDirectories(to.mailbox().inbound());
                if (person != null) {
                    Files.writeString(to.mailbox().inbound().resolve(name + MessageDelivery.PERSON_SUFFIX), person,
                            StandardCharsets.UTF_8);
                } else if (Files.isRegularFile(signature)) {
                    Files.copy(signature, to.mailbox().inbound().resolve(name + ".sig"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                // The message last, through a neighbour and a rename, so a task's pass never sees a message whose
                // companion is still on its way, nor half of one.
                final Path staged = to.mailbox().inbound().resolve("." + name + ".tmp");
                if (bytes != null) {
                    Files.write(staged, bytes);
                } else {
                    Files.copy(message, staged, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(staged, to.mailbox().inbound().resolve(name), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                Files.createDirectories(to.mailbox().record());
                Files.writeString(to.mailbox().record().resolve(ARRIVED), name + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                handed.merge(name, to.container(), (was, also) -> was + ", " + also);
            }
            if (readers.isEmpty()) {
                dropped++;
            }
            Files.deleteIfExists(message);
            Files.deleteIfExists(signature);
            Files.deleteIfExists(sender);
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
            final CommandResult result = context.runner().run(HelperEnvironment.chosen(Command.of(adapter.toString(),
                    "read", reference(name)).withEnvironment(secrets)));
            if (result.successful()) {
                Files.writeString(member.mailbox().record().resolve(MARKED), name + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                marked.add(name);
            } else if (result.exitCode() != TransportSend.TEMPORARY) {
                // Refused (77: a token rejected, or no room of the account holds it): asked again it stays refused.
                Files.writeString(member.mailbox().record().resolve(MARK_REFUSED), name + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                failures.add("the " + member.scheme() + " transport refused to mark a message to "
                        + member.container() + " read (exit " + result.exitCode() + "): "
                        + result.standardError().strip());
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
            final CommandResult result = context.runner().run(HelperEnvironment.chosen(Command.of(adapter.toString(),
                    "receipt", reference, "--by", address).withEnvironment(secrets)));
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
     * {@code inbox/cur} - and that are neither marked read nor refused that by the transport.
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
        arrived.removeAll(lines(mailbox.record().resolve(MARK_REFUSED)));
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
