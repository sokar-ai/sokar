package org.fuin.sokar.app;

import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One task's mailbox on the host: where its messages wait before they leave and after they arrive.
 * <p>
 * Shaped like a mail system, because a mail system already solved this. The half the agent sees is
 * a mail client's - write into {@code outbox/tmp} and rename into {@code outbox/new}, read
 * {@code inbox/new} and rename into {@code inbox/cur} - and a message is complete exactly when it
 * has been renamed, so no reader ever sees half of one. The half the host keeps is a mail server's:
 * queues whose directory names are the state, so a state change is a rename and a crash cannot leave
 * it half done.
 * <p>
 * <strong>Only {@link #box()} is mounted into the container.</strong> Everything else - the refused
 * originals above all, which hold in clear text exactly what the filter refused to let out - stays
 * on the host side of the mount. A mailbox whose root was mounted would hand the agent the evidence
 * of its own refusals and the record written about it.
 * <p>
 * <strong>It lives as long as the task.</strong> Created when the task is created and deleted when
 * the task is removed, under the state directory rather than the runtime one: a conversation has to
 * survive {@code stop}, {@code start} and a machine restart, and the runtime directory is cleared at
 * boot. That is the same reason a task started before a restart cannot be started again today - its
 * sockets were there - and a mailbox must not join them.
 */
public final class Mailbox {

    /** Where the mounted half appears inside the container, beside the two sockets a task gets. */
    public static final String MOUNT = "/run/sokar/mail";

    // The whole tree, not only the refused originals: a mailbox holds what an agent said and what
    // was said to it, and on a machine with several people nobody else's account is a reader of it.
    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);

    private final Path root;

    /**
     * Constructor with the directory this mailbox lives in. Creates nothing.
     *
     * @param root Root of the mailbox, per task.
     */
    public Mailbox(final Path root) {
        this.root = root;
    }

    /**
     * Returns the root of the mailbox, which is the host's half.
     *
     * @return Root directory.
     */
    public Path root() {
        return root;
    }

    /**
     * Returns the only directory that is mounted into the container.
     *
     * @return Directory holding the agent's inbox, outbox and its copies of what was sent.
     */
    public Path box() {
        return root.resolve("box");
    }

    /**
     * Returns where the host writes a message for the agent, before renaming it into
     * {@link #inboxNew()}.
     *
     * @return Directory.
     */
    public Path inboxTmp() {
        return box().resolve("inbox").resolve("tmp");
    }

    /**
     * Returns where a delivered message waits until the agent has read it.
     *
     * @return Directory.
     */
    public Path inboxNew() {
        return box().resolve("inbox").resolve("new");
    }

    /**
     * Returns where the agent renames a message it has read. Nothing but the agent writes here.
     *
     * @return Directory.
     */
    public Path inboxCur() {
        return box().resolve("inbox").resolve("cur");
    }

    /**
     * Returns where the agent writes a message it is still writing.
     *
     * @return Directory.
     */
    public Path outboxTmp() {
        return box().resolve("outbox").resolve("tmp");
    }

    /**
     * Returns where the agent renames a message it has finished, and where the host takes it from.
     *
     * @return Directory.
     */
    public Path outboxNew() {
        return box().resolve("outbox").resolve("new");
    }

    /**
     * Returns where the host writes back the exact bytes that were sent.
     * <p>
     * The mailbox outlives the container, so this is how a restarted agent reads its own half of a
     * conversation - the one thing it cannot reconstruct from its inbox.
     *
     * @return Directory.
     */
    public Path sentCopies() {
        return box().resolve("sent");
    }

    /**
     * Returns where the host puts a message taken out of {@link #outboxNew()}, unchanged.
     *
     * @return Directory.
     */
    public Path incoming() {
        return root.resolve("incoming");
    }

    /**
     * Returns where the filter puts a message it accepted, for the host to dispatch.
     * <p>
     * One directory for every transport: resolving a peer to a transport is policy, and policy is
     * the host's, so the filter never learns where a message is going.
     *
     * @return Directory.
     */
    public Path accepted() {
        return root.resolve("filter").resolve("accepted");
    }

    /**
     * Returns where the filter puts its answer to the sender - a receipt, or a refusal.
     *
     * @return Directory.
     */
    public Path feedback() {
        return root.resolve("filter").resolve("feedback");
    }

    /**
     * Returns where a refused original stays.
     * <p>
     * This is the one directory whose contents are secret <em>because</em> the filter worked, so it
     * is never mounted, never carried anywhere, and goes when the task goes.
     *
     * @return Directory.
     */
    public Path rejected() {
        return root.resolve("filter").resolve("rejected");
    }

    /**
     * Returns where a file that could not be processed at all stays.
     *
     * @return Directory.
     */
    public Path error() {
        return root.resolve("filter").resolve("error");
    }

    /**
     * Returns the filter's index over what was already accepted. A cache, rebuildable, never a
     * source of truth.
     *
     * @return Directory.
     */
    public Path index() {
        return root.resolve("filter").resolve("index");
    }

    /**
     * Returns the queue of one transport.
     *
     * @param transport Transport name, as its adapter reports it.
     * @return Directory.
     */
    public Path queue(final String transport) {
        return root.resolve("queue").resolve(transport);
    }

    /**
     * Returns where a message waits for its transport to carry it.
     *
     * @param transport Transport name.
     * @return Directory.
     */
    public Path queueActive(final String transport) {
        return queue(transport).resolve("active");
    }

    /**
     * Returns where a message waits after a temporary failure.
     *
     * @param transport Transport name.
     * @return Directory.
     */
    public Path queueDeferred(final String transport) {
        return queue(transport).resolve("deferred");
    }

    /**
     * Returns where a message waits for a person - held by policy, or unattributable on arrival.
     *
     * @return Directory.
     */
    public Path hold() {
        return root.resolve("hold");
    }

    /**
     * Returns where another task's transport delivers, before the host hands it to the agent.
     *
     * @return Directory.
     */
    public Path inbound() {
        return root.resolve("inbound");
    }

    /**
     * Returns where a transport writes a message it is still writing.
     * <p>
     * It exists because the host made it. An adapter that found it missing and created it would be
     * delivering into a mailbox whose owner never made it - a task half created, or one being torn
     * down - so a missing {@code tmp} is a temporary failure rather than something to repair from
     * the outside.
     *
     * @return Directory.
     */
    public Path inboundTmp() {
        return inbound().resolve("tmp");
    }

    /**
     * Returns where a message and its signature rest once a transport has taken them.
     *
     * @return Directory.
     */
    public Path sent() {
        return root.resolve("sent");
    }

    /**
     * Returns the hash-chained log of what happened to every message.
     *
     * @return Directory.
     */
    public Path record() {
        return root.resolve("record");
    }

    /**
     * Creates every directory this mailbox needs, readable only by the account that runs Sokar.
     * <p>
     * The host makes the layout because nothing that reads or carries a message may create a
     * directory: a filter or an adapter that finds one missing refuses or defers, so a malformed
     * mailbox is repaired rather than papered over. Doing it twice is not an error - a task that
     * comes back finds its own mailbox and everything in it.
     *
     * @throws IOException Creating a directory failed.
     */
    public void create() throws IOException {
        for (final Path directory : layout()) {
            Files.createDirectories(directory);
            Files.setPosixFilePermissions(directory, OWNER_ONLY);
        }
    }

    /**
     * Returns whether this mailbox exists.
     *
     * @return {@code true} when its root is a directory.
     */
    public boolean exists() {
        return Files.isDirectory(root);
    }

    /**
     * Deletes the mailbox and everything in it.
     * <p>
     * Called when the task is removed and never before: stopping a task, starting it again and
     * restarting the machine all leave the conversation where it was.
     *
     * @throws IOException Deleting failed.
     */
    public void delete() throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            for (final Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Returns the mailbox-shaped tree a message from an {@code external} peer is checked in.
     * <p>
     * Its own root rather than a flag on the filter: the filter derives its whole layout from one
     * directory, so pointing it at a second one checks a message on the way in with the same
     * program, the same rules and the same evidence as on the way out. A message being checked
     * here has not been delivered and is not in the agent's half of the mailbox.
     *
     * @return Directory.
     */
    public Path check() {
        return root.resolve("check");
    }

    /**
     * Returns where a message waits to be checked on the way in.
     *
     * @return Directory.
     */
    public Path checkIncoming() {
        return check().resolve("incoming");
    }

    /**
     * Returns where the filter puts what it accepted on the way in.
     *
     * @return Directory.
     */
    public Path checkAccepted() {
        return check().resolve("filter").resolve("accepted");
    }

    /**
     * Returns where the filter puts what it refused on the way in.
     *
     * @return Directory.
     */
    public Path checkRejected() {
        return check().resolve("filter").resolve("rejected");
    }

    /**
     * Returns where the filter writes why it refused something on the way in.
     *
     * @return Directory.
     */
    public Path checkFeedback() {
        return check().resolve("filter").resolve("feedback");
    }

    /**
     * Returns where the filter puts what it could not read on the way in.
     *
     * @return Directory.
     */
    public Path checkError() {
        return check().resolve("filter").resolve("error");
    }

    private java.util.List<Path> layout() {
        return java.util.List.of(root, box(), inboxTmp(), inboxNew(), inboxCur(), outboxTmp(),
                outboxNew(), sentCopies(), incoming(), accepted(), feedback(), rejected(), error(),
                index(), hold(), inbound(), inboundTmp(), sent(), record(),
                check(), checkIncoming(), checkAccepted(), checkRejected(), checkFeedback(),
                checkError(), check().resolve("sent"), check().resolve("filter").resolve("index"));
    }
}
