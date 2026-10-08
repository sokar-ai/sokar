package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.jspecify.annotations.Nullable;

/**
 * Where Sokar keeps the files of messages: the mailboxes, the keys, the moderation and the transports.
 * <p>
 * One class per area, beside {@link SokarPaths}, which keeps only the roots and what belongs to the machine as a
 * whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record MessagingPaths(SokarPaths paths) {

    /**
     * Returns the root of one task's mailbox.
     * <p>
     * Under the state directory rather than the runtime one, because a conversation has to survive
     * {@code stop}, {@code start} and a machine restart, and the kernel clears the runtime
     * directory at boot. It is the task's: made when the task is made, deleted when the task is
     * removed, and untouched in between.
     *
     * @param container Container name.
     * @return Mailbox root, which is the host's half - only what is under it is mounted.
     */
    public Path mailbox(String container) {
        return mailboxes().resolve(container);
    }

    /**
     * Returns the directory every task's mailbox is in.
     *
     * @return The directory.
     */
    public Path mailboxes() {
        return paths.xdg().state().resolve("mail");
    }

    /**
     * Returns the file holding the key this machine signs messages with.
     * <p>
     * Under the state directory, mode {@code 0600}, and never mounted anywhere. A key a task
     * container could reach is a key it could copy, which is why the task never signs and the host
     * always does - and why what a signature proves is that this installation vouched for the
     * message, not that a particular agent typed it.
     *
     * @return The key file, which may not exist yet.
     */
    public Path messageKey() {
        return paths.xdg().state().resolve("message-key");
    }

    /**
     * Returns the file holding what a person decided about one project's peers: which are held, and
     * how much is asked before a message reaches each.
     * <p>
     * Under the state directory, on the host: per project rather than per task, so a task started later
     * finds what was decided, and never where a task can reach it - a task must not be able to change
     * how closely it is watched.
     *
     * @param project The project's name.
     * @return The file.
     */
    public Path moderation(String project) {
        return paths.xdg().state().resolve("moderation").resolve(project + ".json");
    }

    /**
     * Returns where the users of this machine publish their message keys to each other.
     * <p>
     * Outside any user's home, because it is shared, and made only where an operator has allowed
     * messaging between users. A file here is believed only if it is owned by the user it is named
     * after.
     *
     * @return The directory, which need not exist.
     */
    public Path sharedKeys() {
        return paths.spool().resolve("keys");
    }

    /**
     * Returns the transports installed for this user, their own before the packaged ones.
     *
     * @return The directory.
     */
    public TransportDirectory transportDirectory() {
        return new TransportDirectory(java.util.List.of(paths.xdg().data().resolve("transports"),
                SokarPaths.packaged(TransportDirectory.PACKAGED)));
    }

    /**
     * Returns the filter that decides what a message may contain, or {@code null} when this
     * machine has none.
     * <p>
     * A machine without it sends nothing: the filter is what stands between an agent's outbox and
     * every transport, so its absence is a closed door rather than an open one.
     *
     * @return The executable, or {@code null}.
     */
    public @org.jspecify.annotations.Nullable Path messageFilter() {
        for (final Path candidate : java.util.List.of(
                paths.xdg().data().resolve("filter").resolve(SokarPaths.MESSAGE_FILTER),
                SokarPaths.packaged(Path.of("/usr/libexec/sokar")).resolve(SokarPaths.MESSAGE_FILTER))) {
            if (java.nio.file.Files.isRegularFile(candidate)
                    && java.nio.file.Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the file naming who may speak for a peer.
     * <p>
     * OpenSSH's {@code allowed_signers} format, so the keys that decide what a task may read can be
     * checked with {@code ssh-keygen -Y verify} by somebody who has no Sokar.
     *
     * @return The file, which may not exist.
     */
    public Path allowedSigners() {
        // Named for what it holds, the message keys of other machines, as a project's own file is (the operator,
        // 2026-10-04); the format is still OpenSSH's allowed_signers.
        return paths.xdg().config().resolve("machine-signers");
    }
}
