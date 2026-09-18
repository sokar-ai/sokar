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
     * @param name What a message addresses. Letters, digits, dash and underscore.
     * @param address {@code <transport>:<rest>} - the part before the colon names the transport
     *        that carries it, and the rest is that transport's business.
     * @param trust {@code vouched} for one of the operator's own machines, {@code external} for
     *        anybody else. It decides whether what arrives is checked again here.
     */
    public record Peer(String name, String address, String trust) {

        /** A peer this machine vouches for: its content was checked where it was written. */
        public static final String VOUCHED = "vouched";

        /** Anybody else: what arrives from them is checked here as well. */
        public static final String EXTERNAL = "external";

        private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]*");

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
                throw new ProjectException("A peer name is letters, digits, dash and underscore: "
                        + name);
            }
            if (address == null || !ADDRESS.matcher(address).matches()) {
                throw new ProjectException("A peer's address is '<transport>:<address>', got: "
                        + address);
            }
            if (!VOUCHED.equals(trust) && !EXTERNAL.equals(trust)) {
                throw new ProjectException("A peer is '" + VOUCHED + "' or '" + EXTERNAL
                        + "', got: " + trust);
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
    public Peer peer(final String name) {
        return peers.stream().filter(peer -> peer.name().equals(name)).findFirst().orElse(null);
    }
}
