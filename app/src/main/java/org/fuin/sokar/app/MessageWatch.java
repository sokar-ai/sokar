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

    private volatile boolean running = true;

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
            peers = AllowedSigners.read(context.paths().allowedSigners());
        } catch (final IOException ex) {
            // No key that can be written, or a keyring with a line nobody can read. Both are the
            // operator's to fix, and both stop every mailbox rather than one - so nothing is moved
            // and the next tick tries again.
            return 0;
        }
        int moved = 0;
        for (final Path mailbox : mailboxes) {
            try {
                pass.run(new Mailbox(mailbox), peersOf(mailbox.getFileName().toString()), peers);
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

    @Override
    public void close() {
        running = false;
    }

    private Mail peersOf(final String container) {
        for (final ProjectInventory.Summary summary : new ProjectInventory(context).projects()) {
            if (summary.file() == null || !container.startsWith("sokar-" + summary.name() + "-")) {
                continue;
            }
            try {
                final Project project = GateSupport.project(Path.of(summary.file()));
                return project.mail();
            } catch (final RuntimeException ex) {
                return Mail.none();
            }
        }
        // A mailbox whose project file is gone still receives: what a peer already sent is
        // delivered, and only sending needs to know where anything goes.
        return Mail.none();
    }

    private List<Path> mailboxes() {
        final Path root = context.paths().mailbox("x").getParent();
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
