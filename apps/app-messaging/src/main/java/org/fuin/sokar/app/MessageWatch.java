package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.core.project.Project;
import org.jspecify.annotations.Nullable;

/**
 * Moves every mailbox on this machine along, on a schedule.
 * <p>
 * <strong>Why a timer at all.</strong> The daemon binds no network interface, in this arrangement
 * as in every other, so nothing can be pushed to it: a message arrives because this goes and looks.
 * Until it existed, a message waited in an outbox until a person ran {@code sokar talk pass}, which
 * is fine for a demonstration and not for two agents holding a conversation.
 * <p>
 * <strong>Every mailbox, not every running task.</strong> A mailbox outlives its container, so a
 * task that is stopped still has messages delivered into it - and finds them when it comes back.
 * That is the whole reason the mailbox is not in the runtime directory.
 * <p>
 * <strong>And a timer is not the only thing that starts a pass.</strong> Where both halves of a
 * conversation are on one machine - which is the ordinary case for somebody running several agents
 * on their laptop - waiting up to a minute for an answer that is already lying in a directory is a
 * delay with no cause. So the mailboxes are also watched, and a message that lands is noticed at
 * once. The timer stays, because a watch can miss things and a transport that fetches from
 * elsewhere still has to be gone and asked.
 * <p>
 * <strong>One mailbox's failure is not another's.</strong> A project file that has been deleted, a
 * filter that will not start, a transport whose destination is gone: each stops that mailbox's pass
 * and no other, and the next tick tries again.
 */
public final class MessageWatch implements AutoCloseable {

    /** How often to look, when nothing says otherwise. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

    /** Seconds between passes. {@code 0} turns the passes off entirely. */
    public static final String INTERVAL_VARIABLE = "SOKAR_MESSAGE_SECONDS";

    private final SokarContext context;

    private final Duration interval;

    /** How long to wait after a change, so a burst of files becomes one pass rather than ten. */
    private static final Duration SETTLE = Duration.ofMillis(250);

    private volatile boolean running = true;

    // One pass at a time. The timer and the watch would otherwise walk the same mailbox at once,
    // and two passes moving the same file is a race that ends with a message in neither place.
    private final Object passing = new Object();

    /**
     * Held while messages are moved, and only then: a written message, or a file a long-poll handed in, waits for no
     * conversation step, which asks every conversation's transport and took seconds.
     */
    private final Object moving = new Object();

    private java.nio.file.@org.jspecify.annotations.Nullable WatchService watcher;

    /**
     * Constructor.
     *
     * @param context Where the paths, the runner and the projects come from.
     * @param interval How often to look. Zero or negative means never.
     */
    public MessageWatch(final SokarContext context, final Duration interval) {
        this(context, interval, () -> context.vault().exists() && context.opener().isPresent());
    }

    /**
     * Constructor, saying itself whether the vault is open.
     *
     * @param context The machine.
     * @param interval How often to look.
     * @param vaultOpen Whether this account's vault can be read now without asking anybody.
     */
    MessageWatch(final SokarContext context, final Duration interval,
            final java.util.function.BooleanSupplier vaultOpen) {
        this.context = context;
        this.interval = interval;
        this.vaultOpen = vaultOpen;
    }

    private final java.util.function.BooleanSupplier vaultOpen;

    /** Whether the last pass found the conversations waiting for the vault. */
    private volatile boolean waitedForTheVault;

    /** Whether the vault was open at the last look; shut until the first. */
    private volatile boolean wasOpen;

    /**
     * Tells whether the vault opened since the last look - the first look after the daemon started counts when it is
     * open then. A message written while it was shut waited for the first timed pass, a minute after the start.
     *
     * @return {@code true} once for each time it opened.
     */
    boolean opened() {
        final boolean open = vaultOpen.getAsBoolean();
        final boolean now = open && !wasOpen;
        wasOpen = open;
        return now;
    }

    /** How often a pass's wait looks whether the vault opened for messages that wait for it. */
    static final Duration VAULT_LOOK = Duration.ofSeconds(2);

    /**
     * Notes that a pass found the conversations waiting for the vault.
     */
    void waitedForTheVault() {
        waitedForTheVault = true;
    }

    /**
     * Passes now when the last pass waited for the vault and it is open: left to the next pass, messages waited up to
     * a minute after it was opened.
     *
     * @param pass What a pass is.
     * @return Whether it passed.
     */
    boolean afterTheVault(final Runnable pass) {
        if (!waitedForTheVault || !vaultOpen.getAsBoolean()) {
            return false;
        }
        waitedForTheVault = false;
        pass.run();
        return true;
    }

    /**
     * Returns how often to look on this machine.
     * <p>
     * Switchable off, because a machine whose tasks exchange no messages should not be walking its
     * mailboxes every minute, and because an operator is entitled to refuse a background activity
     * they did not ask for.
     *
     * @param environment Reads an environment variable.
     * @return The interval, or {@link Duration#ZERO} when it is turned off.
     */
    public static Duration interval(final UnaryOperator<String> environment) {
        final String said = environment.apply(INTERVAL_VARIABLE);
        if (said == null || said.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(said.strip()));
        } catch (final NumberFormatException ex) {
            // A misspelt variable falls back to the default rather than turning messages off
            // silently, which would be found weeks later as "nothing ever arrives".
            return DEFAULT_INTERVAL;
        }
    }

    /**
     * Runs one pass over every mailbox on this machine.
     * <p>
     * Never throws: what one mailbox cannot do must not stop the rest.
     *
     * @return How many mailboxes were moved along.
     */
    public int passOnce() {
        synchronized (passing) {
            final List<Path> mailboxes = mailboxes();
            if (mailboxes.isEmpty()) {
                return 0;
            }
            final Moving prepared = prepared(mailboxes);
            if (prepared == null) {
                return 0;
            }
            say(conversations(prepared.pass(), mailboxes));
            synchronized (moving) {
                return move(prepared, mailboxes);
            }
        }
    }

    /**
     * Moves what waits in every mailbox - filter, send, delivery - and nothing else: no conversation is asked
     * anything. What a written message, a file a long-poll handed in, and the vault opening run.
     *
     * @return How many mailboxes were moved along.
     */
    public int moveOnce() {
        synchronized (moving) {
            final List<Path> mailboxes = mailboxes();
            if (mailboxes.isEmpty()) {
                return 0;
            }
            final Moving prepared = prepared(mailboxes);
            if (prepared == null) {
                return 0;
            }
            final TransportConversations conversations =
                    new TransportConversations(context, prepared.pass().transports());
            final java.util.Map<String, String> projects = new java.util.LinkedHashMap<>();
            for (final Path mailbox : mailboxes) {
                final String project = projectOf(mailbox.getFileName().toString());
                if (project != null) {
                    projects.put(mailbox.getFileName().toString(), project);
                }
            }
            prepared.pass().acting((transport, container) -> projects.get(container) == null ? null
                    : conversations.acting(transport, projects.get(container), container));
            return move(prepared, mailboxes);
        }
    }

    /** What a pass moves with: its filter and key, and the peers it may reach. */
    private record Moving(MessagePass pass, List<MessageDelivery.Peer> peers) {
    }

    private @org.jspecify.annotations.Nullable Moving prepared(final List<Path> mailboxes) {
        final MessagePass pass;
        final List<MessageDelivery.Peer> peers;
        try {
            pass = new MessagePass(context.runner(),
                    HostKey.loadOrCreate(context.paths().messaging().messageKey(), "sokar@" + hostName()),
                    context.paths().messaging().messageFilter(), context.paths().messaging().transportDirectory());
            peers = KnownPeers.of(context);
        } catch (final IOException ex) {
            // No key that can be written, or a keyring with a line nobody can read. Both are the
            // operator's to fix, and both stop every mailbox rather than one - so nothing is moved
            // and the next tick tries again.
            return null;
        }
        // Each pass, every mailbox again: one made after the watch started announced its directory before its outbox
        // existed, so the watch on it failed then and was never tried again - and its agent's answers waited for this
        // timer, a minute. Registering a directory twice is the same watch.
        if (watcher != null) {
            mailboxes.forEach(this::watch);
        }
        return new Moving(pass, peers);
    }

    private int move(final Moving prepared, final List<Path> mailboxes) {
        int moved = 0;
        for (final Path mailbox : mailboxes) {
            try {
                final String container = mailbox.getFileName().toString();
                // A mailbox whose project is gone has no peers to send to, so nothing it decides is
                // read; its own name keeps it from reading another project's holds.
                final Mail reachable = peersOf(container);
                // Whom it can reach changes as its project's tasks come and go; the guide beside it does not.
                MailboxGuide.write(new Mailbox(mailbox), reachable);
                final MessagePass.Report report = prepared.pass().run(new Mailbox(mailbox), reachable,
                        prepared.peers(), moderationOf(container));
                woken(container, new Mailbox(mailbox), report);
                moved++;
            } catch (final IOException | RuntimeException ex) {
                continue;
            }
        }
        return moved;
    }

    /**
     * Starts the passes, on a thread of its own.
     * <p>
     * The first pass waits one interval: a daemon that came up and immediately ran every filter on
     * the machine would make starting it expensive, and nothing is waiting for a message that early.
     */
    public void start() {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        // The waiting polls at once, without a pass: started by the first pass, which waits an interval on purpose, a
        // person's first word after the daemon came up waited for it too (Agent Matrix, 2026-10-04).
        BackgroundPass.start("sokar-conversations", () -> {
            try {
                Thread.sleep(SETTLE);
                if (running) {
                    waiting(context.paths().messaging().transportDirectory(),
                            members(mailboxes(), new java.util.LinkedHashMap<>()));
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (final RuntimeException ex) {
                // The first pass starts them.
            }
        });
        BackgroundPass.start("sokar-messages", () -> {
            while (running) {
                // The pass's wait in short steps, each looking whether the vault opened for what waits for it.
                final long round = System.nanoTime() + interval.toNanos();
                boolean passed = false;
                while (running && !passed && System.nanoTime() < round) {
                    try {
                        Thread.sleep(Math.min(VAULT_LOOK.toMillis(),
                                Math.max(1, (round - System.nanoTime()) / 1_000_000)));
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (running && opened()) {
                        // What waited for the vault leaves once it opens, not at the first pass a minute on.
                        moveOnce();
                    }
                    passed = running && afterTheVault(this::passOnce);
                }
                if (running && !passed) {
                    passOnce();
                }
            }
        });
    }

    /**
     * Starts noticing, on a thread of its own.
     * <p>
     * What is watched is the two directories a message appears in without anybody being told: the
     * agent's outbox, and the inbound directory a local transport delivers into. The mail root is
     * watched too, so a mailbox made after this started is picked up rather than being the one
     * that stays slow.
     *
     * @return {@code true} when the machine can watch directories at all. A machine that cannot
     *         still has the timer, which is why this reports rather than throws.
     */
    public boolean startNotices() {
        final Path root = context.paths().messaging().mailboxes();
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
        try {
            watcher = root.getFileSystem().newWatchService();
            register(root);
            for (final Path mailbox : mailboxes()) {
                watch(mailbox);
            }
        } catch (final IOException | RuntimeException ex) {
            watcher = null;
            return false;
        }
        BackgroundPass.start("sokar-message-notices", this::notices);
        return true;
    }

    private void notices() {
        final java.nio.file.WatchService service = watcher;
        if (service == null) {
            return;
        }
        while (running) {
            final java.nio.file.WatchKey key;
            try {
                key = service.take();
            } catch (final InterruptedException | java.nio.file.ClosedWatchServiceException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            // A new mailbox brings two more directories to watch, and nothing else here would
            // ever look at it again.
            for (final java.nio.file.WatchEvent<?> event : key.pollEvents()) {
                if (key.watchable() instanceof Path directory
                        && event.context() instanceof Path name
                        && Files.isDirectory(directory.resolve(name))) {
                    watch(directory.resolve(name));
                }
            }
            key.reset();
            try {
                // Let the rest of a burst arrive: an agent writing five messages should cause one
                // pass, not five, and a file being renamed into place arrives as two events.
                Thread.sleep(SETTLE);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running) {
                // Only the moving: a written message leaves now, not after every conversation was asked.
                moveOnce();
            }
        }
    }

    private void watch(final Path mailbox) {
        final Mailbox box = new Mailbox(mailbox);
        // And accepted/, where a person's release puts a held message back: it went out only at the next minute's pass
        // (walk 10, 2026-10-04).
        for (final Path directory : List.of(box.outboxNew(), box.inbound(), box.accepted())) {
            try {
                register(directory);
            } catch (final IOException | RuntimeException ex) {
                // A mailbox that cannot be watched is one the timer still reaches. Refusing to
                // watch anything because one directory is missing would be worse.
                continue;
            }
        }
    }

    private void register(final Path directory) throws IOException {
        final java.nio.file.WatchService service = watcher;
        if (service != null && Files.isDirectory(directory)) {
            directory.register(service, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
        }
    }

    @Override
    public void close() {
        running = false;
        final java.nio.file.WatchService service = watcher;
        if (service != null) {
            try {
                service.close();
            } catch (final IOException ex) {
                // Closing a watch service that is already gone is not a failure worth reporting:
                // the daemon is stopping either way.
                watcher = null;
            }
        }
    }

    /**
     * Returns the name of the project a task's container belongs to, or {@code null}.
     *
     * @param container The container.
     * @return Its project's name.
     */
    /**
     * Runs every project's conversation once, so what arrived there is in each task's inbound before that
     * task's pass delivers, and lets the pass send as each task.
     *
     * @param pass The pass that follows.
     * @param mailboxes The mailboxes it moves along.
     * @return What the conversations could not do.
     */
    List<String> conversations(final MessagePass pass, final List<Path> mailboxes) {
        final TransportConversations conversations = new TransportConversations(context, pass.transports());
        final java.util.Map<String, String> projects = new java.util.LinkedHashMap<>();
        final List<TransportConversations.Member> members = members(mailboxes, projects);
        waiting(pass.transports(), members);
        final List<String> failures = new ArrayList<>(conversations.pass(members).failures());
        // Said, once while it lasts: a locked vault stopped every conversation without a word.
        final String waits = members.isEmpty() ? "" : Conversations.vaultWait(context);
        if (!waits.isEmpty()) {
            failures.add("messages wait: " + waits);
            waitedForTheVault();
        }
        pass.acting((transport, container) -> projects.get(container) == null ? null
                : conversations.acting(transport, projects.get(container), container));
        return failures;
    }

    /**
     * Returns the tasks on this machine that take part in a conversation, with their projects.
     *
     * @param mailboxes The mailboxes a pass moves.
     * @param projects Filled with each of those tasks' project.
     * @return Each task once per conversation it takes part in.
     */
    private List<TransportConversations.Member> members(final List<Path> mailboxes,
            final java.util.Map<String, String> projects) {
        final List<TransportConversations.Member> members = new ArrayList<>();
        for (final Path mailbox : mailboxes) {
            final String container = mailbox.getFileName().toString();
            final String project = projectOf(container);
            if (project != null) {
                projects.put(container, project);
            }
        }
        // Every task of those projects on this machine takes part, not only the ones this pass moves: what arrives in a
        // conversation is for whichever task it names. A pass for one task handed out to that task alone, so a message
        // for its peer named nobody here and was deleted - lost after the transport had delivered it.
        final java.util.Set<Path> taking = new java.util.LinkedHashSet<>(mailboxes);
        final Path all = context.paths().messaging().mailbox("sokar-x-x").getParent();
        if (all != null && Files.isDirectory(all)) {
            try (java.util.stream.Stream<Path> found = Files.list(all)) {
                found.filter(Files::isDirectory)
                        .filter(each -> projects.containsValue(projectOf(each.getFileName().toString())))
                        .forEach(taking::add);
            } catch (final java.io.IOException ex) {
                // The ones asked for still take part.
            }
        }
        for (final Path mailbox : taking) {
            final String container = mailbox.getFileName().toString();
            final String project = projectOf(container);
            if (project == null) {
                continue;
            }
            for (final String scheme : peersOf(container).conversations()) {
                members.add(new TransportConversations.Member(container, new Mailbox(mailbox), project, scheme));
            }
        }
        return members;
    }

    /**
     * Says what a conversation could not do, once per sentence: a transport that is misconfigured stays so
     * until somebody fixes it, and saying it every minute buries everything else.
     *
     * @param failures What went wrong this pass.
     */
    /** Each conversation's tasks as the last pass found them, for its waiting poll. */
    private final java.util.Map<String, List<TransportConversations.Member>> conversing =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Keeps a poll waiting on the server for each conversation whose transport can wait, so a person's word reaches
     * the task in seconds rather than at the next minute's pass (walk 10, 2026-10-04).
     */
    private void waiting(final TransportDirectory transports, final List<TransportConversations.Member> members) {
        final java.util.Map<String, List<TransportConversations.Member>> groups = new java.util.LinkedHashMap<>();
        members.forEach(member -> groups.computeIfAbsent(TransportConversations.key(member.scheme(), member.project()),
                key -> new ArrayList<>()).add(member));
        conversing.keySet().retainAll(groups.keySet());
        conversing.putAll(groups);
        if (interval.isZero() || interval.isNegative()) {
            // A pass by hand, never a daemon: nothing keeps a loop running after it.
            return;
        }
        // And each task's direct chats, in a loop of its own, so no other poll of its account runs beside it.
        for (final TransportConversations.Member member : members) {
            final Path adapter = transports.find(member.scheme());
            final String key = TransportConversations.key(member.scheme(), member.container());
            if (adapter == null) {
                continue;
            }
            final TransportDescription described = TransportDescription.of(context.runner(), adapter);
            if (!described.takes(TransportConversations.WAITS) || !described.takes(TransportConversations.DIRECT)
                    || !TransportConversations.WAITING.add(key)) {
                continue;
            }
            final String group = TransportConversations.key(member.scheme(), member.project());
            BackgroundPass.start("sokar-direct-" + member.container(), () -> {
                try {
                    while (running && conversing.getOrDefault(group, List.of()).stream()
                            .anyMatch(each -> each.container().equals(member.container()))) {
                        final long asked = System.nanoTime();
                        final TransportConversations conversations = new TransportConversations(context, transports);
                        final TransportConversations.Outcome got = conversations.waitDirect(member);
                        say(got.failures());
                        if (!got.handed().isEmpty() && running) {
                            // Delivered now, then marked read: no conversation step stands in between.
                            moveOnce();
                            say(conversations.confirm(List.of(member)));
                        }
                        Thread.sleep(pause(got, Duration.ofNanos(System.nanoTime() - asked)).toMillis());
                    }
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    TransportConversations.WAITING.remove(key);
                }
            });
        }
        groups.forEach((key, group) -> {
            final Path adapter = transports.find(group.getFirst().scheme());
            if (adapter == null || !TransportDescription.of(context.runner(), adapter).takes(TransportConversations.WAITS)
                    || !TransportConversations.WAITING.add(key)) {
                return;
            }
            BackgroundPass.start("sokar-conversation-" + group.getFirst().project(), () -> {
                try {
                    while (running) {
                        final List<TransportConversations.Member> now = conversing.get(key);
                        if (now == null || now.isEmpty()) {
                            return;
                        }
                        final long asked = System.nanoTime();
                        final TransportConversations conversations = new TransportConversations(context, transports);
                        final TransportConversations.Outcome got = conversations.waitOnce(now);
                        say(got.failures());
                        if (!got.handed().isEmpty() && running) {
                            moveOnce();
                            say(conversations.confirm(now));
                        }
                        Thread.sleep(pause(got, Duration.ofNanos(System.nanoTime() - asked)).toMillis());
                    }
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    TransportConversations.WAITING.remove(key);
                }
            });
        });
    }

    /** How long a loop that waits on a transport rests when the transport did not wait. */
    static final Duration IDLE = Duration.ofSeconds(5);

    /** Shorter than this, a wait that brought nothing did not wait. */
    static final Duration WAITED = Duration.ofSeconds(2);

    /**
     * Returns how long a loop rests before it waits on its transport again: not at all after something came or after a
     * wait that waited, {@link #IDLE} after a failure or a wait that came back at once with nothing. Without a vault
     * entry for an account, or with the vault shut, a wait returns at once, and two such loops turned two cores for
     * hours.
     *
     * @param got What the wait brought.
     * @param took How long it took.
     * @return The rest.
     */
    static Duration pause(final TransportConversations.Outcome got, final Duration took) {
        if (!got.failures().isEmpty()) {
            return IDLE;
        }
        return got.handed().isEmpty() && took.compareTo(WAITED) < 0 ? IDLE : Duration.ZERO;
    }

    private void say(final List<String> failures) {
        for (final String failure : failures) {
            if (reported.add(failure)) {
                System.err.println("sokard: " + failure);
            }
        }
        reported.retainAll(failures);
    }

    private final java.util.Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Wakes a task's agent at rest when what this pass gave it names it: {@code @agentname}, or a direct chat.
     * Once a pass, however many arrived; never for the room's other talk, which it reads when it next looks.
     */
    private void woken(final String container, final Mailbox mailbox, final MessagePass.Report report) {
        final AgentWake wake = new AgentWake(context);
        wake.announce(mailbox, container, projectOf(container));
        // And of a file that arrived while it worked: a hand-in tells it at once only when it waits at its prompt.
        wake.announceFiles(container);
    }

    private @Nullable String projectOf(final String container) {
        return ProjectNames.of(container, labels(),
                new ProjectInventory(context).projects().stream().map(ProjectInventory.Summary::name).toList());
    }

    /** How long the runtime's labels are taken as they were: a pass asks for every mailbox, and podman is slow. */
    private static final long LABELS_KEPT_MILLIS = 5000;

    private volatile java.util.Map<String, String> labels = java.util.Map.of();

    private volatile long labelsAt;

    private java.util.Map<String, String> labels() {
        final long now = System.currentTimeMillis();
        if (now - labelsAt > LABELS_KEPT_MILLIS) {
            labels = ProjectNames.labels(context);
            labelsAt = now;
        }
        return labels;
    }

    /**
     * Returns how a task's messages are watched: by its project's file, and held by a person.
     *
     * @param container The task.
     * @return Its project's moderation; for a mailbox whose project or its file is gone, one where every peer
     *         waits for a person, under the project's name or else the task's own.
     */
    Moderation moderationOf(final String container) {
        final String owner = projectOf(container);
        for (final ProjectInventory.Summary summary : new ProjectInventory(context).projects()) {
            if (summary.file() == null || !summary.name().equals(owner)) {
                continue;
            }
            try {
                return Moderation.of(context, GateSupport.project(Path.of(summary.file())));
            } catch (final RuntimeException ex) {
                break;
            }
        }
        return Moderation.of(context.paths(), owner == null ? container : owner);
    }

    Mail peersOf(final String container) {
        final String owner = projectOf(container);
        for (final ProjectInventory.Summary summary : new ProjectInventory(context).projects()) {
            if (summary.file() == null || !summary.name().equals(owner)) {
                continue;
            }
            try {
                final Project project = GateSupport.project(Path.of(summary.file()));
                return withSiblings(project, container);
            } catch (final RuntimeException ex) {
                return Mail.none();
            }
        }
        // A mailbox whose project file is gone still receives: what a peer already sent is
        // delivered, and only sending needs to know where anything goes.
        return Mail.none();
    }

    /**
     * Returns the project's written peers with its own other tasks added.
     * <p>
     * <strong>Nobody writes these down.</strong> A project is a unit of work over one or more
     * repositories and a task works on exactly one of them, so coordination between repositories
     * happens between tasks - and a peer list somebody has to maintain for tasks this machine
     * starts and stops by itself would be wrong most of the time. The tasks of one project are
     * peers because they are tasks of one project.
     * <p>
     * <strong>A written peer of the same name wins.</strong> That is how an exception stays
     * possible and stays explicit: naming a task in {@code mail.peers} sends to what the file says
     * rather than to the task next door.
     * <p>
     * <strong>Vouched, because this machine wrote them.</strong> Both mailboxes belong to this
     * installation and this Unix user; a message between them was checked where it was written.
     *
     * @param project The project both tasks belong to.
     * @param container The task asking, which is never its own peer.
     * @return The peers this task may address.
     */
    Mail withSiblings(final Project project, final String container) {
        final List<org.fuin.sokar.core.project.Mail.Peer> peers =
                new ArrayList<>(project.mail().peers());
        // The people in the project's conversation, whom nobody has to write down.
        final org.fuin.sokar.core.project.Mail.Peer people = project.mail().people();
        if (people != null) {
            peers.add(people);
        }
        // And each of them by the name they joined with, reached in a direct chat with them (the operator, 2026-10-04).
        final java.util.Set<String> rooms = project.mail().conversations();
        if (!rooms.isEmpty()) {
            final List<String> named = peers.stream().map(org.fuin.sokar.core.project.Mail.Peer::name).toList();
            Conversations.members(context, project).forEach((person, account) -> {
                if (!named.contains(person) && !org.fuin.sokar.core.project.Mail.PEOPLE.equals(person)) {
                    try {
                        peers.add(new org.fuin.sokar.core.project.Mail.Peer(person,
                                rooms.iterator().next() + ":" + account,
                                org.fuin.sokar.core.project.Mail.Peer.EXTERNAL));
                    } catch (final org.fuin.sokar.core.project.ProjectException ex) {
                        // A name no peer can carry: that person is reached in the room, as all of them are.
                    }
                }
            });
        }
        final List<String> written = peers.stream()
                .map(org.fuin.sokar.core.project.Mail.Peer::name).toList();
        for (final org.fuin.sokar.runtime.ContainerSummary task : context.podman().sokarTasks()) {
            final String name = task.name();
            if (name.equals(container) || !name.startsWith("sokar-" + project.name() + "-")
                    || TaskInventory.ofAnotherProject(task, project.name()) != null) {
                // Its label says whose it is: 'web-app''s tasks begin with 'sokar-web-' too.
                continue;
            }
            final String sibling = org.fuin.sokar.runtime.ContainerName.taskIn(project.name(), name);
            if (sibling == null || written.contains(sibling)) {
                continue;
            }
            // Through the project's conversation, on whichever transport keeps it: a person in it sees what the
            // tasks say to each other. A project without one is standalone - its tasks do not message each
            // other at all (decided by the operator on 2026-09-30, when the local transport was retired).
            final java.util.Set<String> conversations = project.mail().conversations();
            if (conversations.isEmpty()) {
                break;
            }
            peers.add(new org.fuin.sokar.core.project.Mail.Peer(sibling, conversations.iterator().next() + ":",
                    org.fuin.sokar.core.project.Mail.Peer.VOUCHED));
        }
        return new Mail(List.copyOf(peers), project.mail().transports(), project.mail().outgoingReported());
    }

    private List<Path> mailboxes() {
        final Path root = context.paths().messaging().mailboxes();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            final List<Path> found = new ArrayList<>(entries.filter(Files::isDirectory).toList());
            found.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return found;
        } catch (final IOException ex) {
            return List.of();
        }
    }

    private String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (final java.net.UnknownHostException ex) {
            return "localhost";
        }
    }
}
