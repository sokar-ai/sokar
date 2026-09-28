package org.fuin.sokar.core.project;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Who a project's tasks may exchange messages with.
 * <p>
 * A peer is a <strong>name</strong> here and an address only on the host. A task never sees the
 * address: it writes {@code metadata.to: reviewer} into a message and the host decides that
 * {@code reviewer} is a directory on this machine, a branch in a repository or a mailbox somewhere
 * else. That is what keeps a container from learning where anything is.
 *
 * @param peers Who this project's tasks may address, in the order the file names them.
 */
public record Mail(List<Peer> peers) {

    /**
     * A name a task uses, and what the host resolves it to.
     *
     * @param name What a message addresses. Letters, digits, dash, underscore, dot and at sign -
     *        wide enough to hold an OpenSSH principal, which is how an arriving message names its
     *        sender.
     * @param address {@code <transport>:<rest>} - the part before the colon names the transport
     *        that carries it, and the rest is that transport's business.
     * @param trust {@code vouched} for one of the operator's own machines, {@code external} for
     *        anybody else. It decides whether what arrives is checked again here.
     * @param perDay How many messages a day a task may exchange with this peer, each way. The
     *        work a message causes is paid for by whoever receives it, so the limit is a promise
     *        in both directions rather than a throttle on sending.
     */
    public record Peer(String name, String address, String trust, int perDay) {

        /**
         * What a peer that names no limit gets.
         * <p>
         * High enough not to interrupt a conversation anybody actually has, low enough that two
         * agents answering each other in a loop stop within a day rather than filling a disk.
         */
        public static final int DEFAULT_PER_DAY = 200;

        /**
         * Constructor for a peer with the default limit.
         *
         * @param name Peer name.
         * @param address Transport and its address.
         * @param trust Trust level.
         */
        public Peer(String name, String address, String trust) {
            this(name, address, trust, DEFAULT_PER_DAY);
        }

        /** A peer this machine vouches for: its content was checked where it was written. */
        public static final String VOUCHED = "vouched";

        /** Anybody else: what arrives from them is checked here as well. */
        public static final String EXTERNAL = "external";

        /**
         * Returns whether what this peer sends is checked on the way in.
         *
         * @return {@code true} for an external peer. Trust is a property of the peer, never of
         *         what carried the message.
         */
        public boolean external() {
            return EXTERNAL.equals(trust);
        }

        // A peer name has to be able to hold an OpenSSH principal, because that is what a peer
        // is identified by when its message arrives: 'sokar talk key' prints 'sokar@<host>' and a
        // project that could not name that would be unable to address the machine that produced
        // it. Measured on the VM, where the obvious path produced an identity nobody could
        // address. It reaches no file name and no argument list, only 'metadata.to' and a key in
        // the moderation file, so the dot and the at sign cost nothing.
        private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.@-]*");

        // The transport's name reaches a file name and an argument list, so it is as narrow as a
        // peer name. The rest of an address is whatever that transport understands.
        private static final Pattern ADDRESS = Pattern.compile("([a-z0-9][a-z0-9-]*):(.+)");

        /**
         * Constructor with checks.
         *
         * @param name Peer name.
         * @param address Transport and its address.
         * @param trust Trust level.
         */
        public Peer {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new ProjectException("A peer name is letters, digits, dash, underscore, dot"
                        + " and at sign, starting with a letter or digit: " + name);
            }
            if (address == null || !ADDRESS.matcher(address).matches()) {
                throw new ProjectException("A peer's address is '<transport>:<address>', got: "
                        + address);
            }
            if (!VOUCHED.equals(trust) && !EXTERNAL.equals(trust)) {
                throw new ProjectException("A peer is '" + VOUCHED + "' or '" + EXTERNAL
                        + "', got: " + trust);
            }
            if (perDay < 1) {
                throw new ProjectException("A peer's 'per_day' is at least 1, got: " + perDay);
            }
        }

        /**
         * Returns which transport carries a message to this peer.
         *
         * @return The part of the address before the colon.
         */
        public String transport() {
            return address.substring(0, address.indexOf(':'));
        }

        /**
         * Returns what the transport is handed.
         *
         * @return The part of the address after the colon.
         */
        public String destination() {
            return address.substring(address.indexOf(':') + 1);
        }
    }

    /**
     * Returns a project that exchanges no messages.
     *
     * @return Empty.
     */
    public static Mail none() {
        return new Mail(List.of());
    }

    /**
     * Returns the peer of one name.
     *
     * @param name As a message addresses it.
     * @return The peer, or {@code null} when this project may not address it.
     */
    public @org.jspecify.annotations.Nullable Peer peer(final String name) {
        return peers.stream().filter(peer -> peer.name().equals(name)).findFirst().orElse(null);
    }
}
