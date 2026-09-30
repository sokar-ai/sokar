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

    private java.nio.file.@org.jspecify.annotations.Nullable WatchService watcher;

    /**
     * Constructor.
     *
     * @param context Where the paths, the runner and the projects come from.
     * @param interval How often to look. Zero or negative means never.
     */
    public MessageWatch(final SokarContext context, final Duration interval) {
        this.context = context;
        this.interval = interval;
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
            return pass();
        }
    }

    private int pass() {
        final List<Path> mailboxes = mailboxes();
        if (mailboxes.isEmpty()) {
            return 0;
        }
        final MessagePass pass;
        final List<MessageDelivery.Peer> peers;
        try {
            pass = new MessagePass(context.runner(),
                    HostKey.loadOrCreate(context.paths().messageKey(), "sokar@" + hostName()),
                    context.paths().messageFilter(), context.paths().transportDirectory());
            peers = KnownPeers.of(context);
        } catch (final IOException ex) {
            // No key that can be written, or a keyring with a line nobody can read. Both are the
            // operator's to fix, and both stop every mailbox rather than one - so nothing is moved
            // and the next tick tries again.
            return 0;
        }
        say(conversations(pass, mailboxes));

        int moved = 0;
        for (final Path mailbox : mailboxes) {
            try {
                final String container = mailbox.getFileName().toString();
                final String project = projectOf(container);
                // A mailbox whose project is gone has no peers to send to, so nothing it decides is
                // read; its own name keeps it from reading another project's decisions.
                pass.run(new Mailbox(mailbox), peersOf(container), peers,
                        Moderation.of(context.paths(), project == null ? container : project));
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
        Thread.ofVirtual().name("sokar-messages").start(() -> {
            while (running) {
                try {
                    Thread.sleep(interval);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running) {
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
        final Path root = context.paths().mailboxes();
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
        Thread.ofVirtual().name("sokar-message-notices").start(this::notices);
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
                passOnce();
            }
        }
    }

    private void watch(final Path mailbox) {
        final Mailbox box = new Mailbox(mailbox);
        for (final Path directory : List.of(box.outboxNew(), box.inbound())) {
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
        final List<TransportConversations.Member> members = new ArrayList<>();
        final java.util.Map<String, String> projects = new java.util.LinkedHashMap<>();
        for (final Path mailbox : mailboxes) {
            final String container = mailbox.getFileName().toString();
            final String project = projectOf(container);
            if (project == null) {
                continue;
            }
            projects.put(container, project);
            for (final String scheme : peersOf(container).conversations()) {
                members.add(new TransportConversations.Member(container, new Mailbox(mailbox), project, scheme));
            }
        }
        final List<String> failures = conversations.pass(members).failures();
        pass.acting((transport, container) -> projects.get(container) == null ? null
                : conversations.acting(transport, projects.get(container), container));
        return failures;
    }

    /**
     * Says what a conversation could not do, once per sentence: a transport that is misconfigured stays so
     * until somebody fixes it, and saying it every minute buries everything else.
     *
     * @param failures What went wrong this pass.
     */
    private void say(final List<String> failures) {
        for (final String failure : failures) {
            if (reported.add(failure)) {
                System.err.println("sokard: " + failure);
            }
        }
        reported.retainAll(failures);
    }

    private final java.util.Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private @Nullable String projectOf(final String container) {
        for (final ProjectInventory.Summary summary : new ProjectInventory(context).projects()) {
            if (container.startsWith("sokar-" + summary.name() + "-")) {
                return summary.name();
            }
        }
        return null;
    }

    Mail peersOf(final String container) {
        for (final ProjectInventory.Summary summary : new ProjectInventory(context).projects()) {
            if (summary.file() == null || !container.startsWith("sokar-" + summary.name() + "-")) {
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
        final List<String> written = peers.stream()
                .map(org.fuin.sokar.core.project.Mail.Peer::name).toList();
        for (final org.fuin.sokar.runtime.ContainerSummary task : context.podman().sokarTasks()) {
            final String name = task.name();
            if (name.equals(container) || !name.startsWith("sokar-" + project.name() + "-")) {
                continue;
            }
            final String sibling = org.fuin.sokar.runtime.ContainerName.taskIn(project.name(), name);
            if (sibling == null || written.contains(sibling)) {
                continue;
            }
            // A project with a conversation of its own - a room - talks through it, its own tasks too: a
            // person in it sees what the tasks say to each other. Otherwise the local transport takes the
            // absolute path of the recipient's inbound directory and derives nothing itself - the host
            // holds the peer table, which is this method.
            final java.util.Set<String> conversations = project.mail().conversations();
            peers.add(new org.fuin.sokar.core.project.Mail.Peer(sibling,
                    conversations.isEmpty() ? "local:" + new Mailbox(context.paths().mailbox(name)).inbound()
                            : conversations.iterator().next() + ":",
                    org.fuin.sokar.core.project.Mail.Peer.VOUCHED));
        }
        return new Mail(List.copyOf(peers), project.mail().transports());
    }

    private List<Path> mailboxes() {
        final Path root = context.paths().mailboxes();
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
