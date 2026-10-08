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

    /** Where the task's agent reads how its mailbox works, inside the container. */
    public static final String GUIDE = MOUNT + "/README.md";

    /** Where the task's agent reads whom it can reach right now, inside the container. */
    public static final String CARD = MOUNT + "/agent-card.json";

    // The whole tree, not only the refused originals: a mailbox holds what an agent said and what
    // was said to it, and on a machine with several people nobody else's account is a reader of it.
    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);

    /** A directory of the box the agent passes and reads but does not write. */
    private static final Set<PosixFilePermission> AGENT_READS =
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");

    /** A directory of the box the agent writes or renames in: not sticky, since each side moves the other's files. */
    private static final Set<PosixFilePermission> AGENT_WRITES =
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx");

    /** A message the host hands the agent. */
    private static final Set<PosixFilePermission> AGENT_READABLE =
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--");

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
     * Returns the host's side of {@link #GUIDE}.
     *
     * @return File.
     */
    public Path guide() {
        return box().resolve("README.md");
    }

    /**
     * Returns how an agent is started in this task: told where its guide is, when the task has one and the agent
     * declares a way to take it.
     * <p>
     * One rule for each way of starting it, attended, unattended and continued, so none of them starts an agent that
     * does not know its mailbox. A task without a mailbox has no guide and gets nothing added.
     *
     * @param definition The agent.
     * @param command How it is started otherwise.
     * @return The command.
     */
    public java.util.List<String> instructing(final org.fuin.sokar.agent.api.AgentDefinition definition,
            final java.util.List<String> command) {
        return Files.isRegularFile(guide(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                ? definition.instructed(command, GUIDE) : command;
    }

    /**
     * Returns the host's side of {@link #CARD}.
     *
     * @return File.
     */
    public Path card() {
        return box().resolve("agent-card.json");
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
     * Refuses a box whose directories the task replaced with links.
     * <p>
     * The box is the task's own, mounted into its container: a link in place of its outbox led the host to read, sign
     * and delete files wherever its account can, and one in place of its inbox to write there. Nothing is taken from
     * or put into the box until it is directories again.
     *
     * @throws IOException If one of them is a link, or not a directory.
     */
    public void refuseLinks() throws IOException {
        for (final Path directory : java.util.List.of(box(), box().resolve("inbox"), inboxTmp(), inboxNew(), inboxCur(),
                box().resolve("outbox"), outboxTmp(), outboxNew())) {
            if (Files.isSymbolicLink(directory)
                    || Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            && !Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(directory + " is a link or not a directory; nothing is taken from or put into"
                        + " this task's box until it is a directory again");
            }
        }
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
     * Creates every directory this mailbox needs: the box open to the task's agent, everything else readable only by
     * the account that runs Sokar.
     * <p>
     * <strong>The box is open because the agent is another user.</strong> Under rootless podman the account running
     * Sokar is root in the container, and the agent is a user of its own there, mapped to one of the account's
     * subordinate ids. Found in walk 9 on 2026-10-04: with every directory the account's alone, the agent could not
     * even list its inbox. Only the box is mounted, so only the box opens; the mailbox's own directory around it stays
     * the account's alone, which keeps every other account on the host out of the box too. What the host takes from
     * the outbox it reads without following a link and only as a regular file.
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
            // Each time, so a task made before the box opened finds it open when it comes back.
            Files.setPosixFilePermissions(directory, permissionsOf(directory));
        }
    }

    private Set<PosixFilePermission> permissionsOf(final Path directory) {
        if (directory.equals(inboxNew()) || directory.equals(inboxCur()) || directory.equals(outboxTmp())
                || directory.equals(outboxNew())) {
            return AGENT_WRITES;
        }
        return directory.startsWith(box()) ? AGENT_READS : OWNER_ONLY;
    }

    /**
     * Hands the agent a message staged in {@link #inboxTmp()}: readable by it, then renamed into {@link #inboxNew()}.
     * <p>
     * Readable whatever the umask that wrote it or the file it came from: a message that arrived from a transport was
     * the account's alone, and the agent could see its name and not read it.
     *
     * @param staged The message, in {@link #inboxTmp()}.
     * @return Where it is now.
     * @throws IOException Changing or moving it failed.
     */
    public Path intoInbox(final Path staged) throws IOException {
        Files.setPosixFilePermissions(staged, AGENT_READABLE);
        final Path delivered = inboxNew().resolve(staged.getFileName());
        Files.move(staged, delivered, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return delivered;
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
     * Returns where a person's own messages wait to be taken.
     * <p>
     * <strong>Outside {@link #box()}, and that is the whole point.</strong> A person's message
     * carries {@code ROLE_USER}, and the filter accepts that role on the word of this host - so
     * this host has to make it true. If a person wrote into the same directory the container can
     * write into, a task could forge a message from its operator, and the role would mean nothing.
     * Here the task cannot see it, let alone write it.
     *
     * @return Directory.
     */
    public Path person() {
        return root.resolve("person");
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
        return java.util.List.of(root, box(), box().resolve("inbox"), box().resolve("outbox"), inboxTmp(), inboxNew(),
                inboxCur(), outboxTmp(),
                outboxNew(), sentCopies(), incoming(), accepted(), feedback(), rejected(), error(),
                index(), hold(), inbound(), inboundTmp(), sent(), record(),
                person(),
                check(), checkIncoming(), checkAccepted(), checkRejected(), checkFeedback(),
                checkError(), check().resolve("sent"), check().resolve("filter").resolve("index"));
    }
}
