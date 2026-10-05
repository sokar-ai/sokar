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
 * @param transports What the project says to each transport under {@code mail.transports.<scheme>}, as it
 *        is written: Sokar reads none of it and hands it to the transport, whose settings they are.
 * @param outgoingReported Whether the outgoing filter only reports what it would refuse, as
 *        {@code mail.outgoing_filter: reporting} says. By default it refuses: reporting only, a payload in plain
 *        text left unless a person happened to hold it.
 * @param rules How closely a message is watched for each class of peer, as {@code mail.rules} says.
 */
public record Mail(List<Peer> peers, java.util.Map<String, Object> transports, boolean outgoingReported,
        Rules rules) {

    /** A message waits until a person releases or refuses it. */
    public static final String PROMPT = "prompt";

    /** A message goes once the filter has accepted it. */
    public static final String ALLOW = "allow";

    /** A message never goes, and its sender is told. */
    public static final String DENY = "deny";

    /** Like {@link #ALLOW}. It never switches the filter off: nothing does. */
    public static final String OFF = "off";

    /** Every mode, the same four a task's clearance has. */
    public static final java.util.Set<String> MODES = java.util.Set.of(PROMPT, ALLOW, DENY, OFF);

    /**
     * The people in the project's conversation, as a task addresses them: a peer every project with a conversation has
     * without anybody writing it.
     * <p>
     * The MVP's rule (the operator, 2026-10-04): reaching the room's people needs no step from anyone. Before, a task
     * could reach them only through a peer a person had to add by hand, and nothing said so.
     */
    public static final String PEOPLE = "people";

    /** The setting under {@code mail} that holds the rules. */
    public static final String RULES = "rules";

    /**
     * Constructor for a project whose file sets no rule, so every class of peer gets its default.
     *
     * @param peers Who this project's tasks may address.
     * @param transports Each transport's settings, as written.
     * @param outgoingReported Whether the outgoing filter only reports.
     */
    public Mail(List<Peer> peers, java.util.Map<String, Object> transports, boolean outgoingReported) {
        this(peers, transports, outgoingReported, Rules.UNSAID);
    }

    /**
     * How closely a message is watched, by whom it is for: set once in the project, for every task of it.
     * <p>
     * Each is one of {@link #MODES}, or {@code null} where the file says nothing and the default holds:
     * {@code allow} for the project's own tasks and its conversation, {@code deny} for everyone else. What
     * the filter passes goes at once; only what it flags waits for a person (the operator in walk 10,
     * 2026-10-04: "a release is only needed when the filter flags something as a problem").
     *
     * @param project For the project's own tasks, which it never names: they reach each other by task name.
     * @param room For a peer reached through the project's conversation, {@code <transport>:}.
     * @param others For every other peer the file names.
     */
    public record Rules(@org.jspecify.annotations.Nullable String project,
            @org.jspecify.annotations.Nullable String room, @org.jspecify.annotations.Nullable String others) {

        /** A file that sets no rule. */
        public static final Rules UNSAID = new Rules(null, null, null);

        /**
         * Constructor with checks.
         *
         * @param project For the project's own tasks.
         * @param room For the project's conversation.
         * @param others For everyone else.
         */
        public Rules {
            for (final String mode : java.util.Arrays.asList(project, room, others)) {
                if (mode != null && !MODES.contains(mode)) {
                    throw new ProjectException("'mail." + RULES + "' takes " + modes() + ", got: " + mode);
                }
            }
        }
    }

    /**
     * Returns how closely a message to one name is watched, from the file alone.
     *
     * @param name As a message addresses it.
     * @return One of {@link #MODES}: the peer's own, else its class's rule, else that class's default. A
     *         name the file does not list is one of the project's own tasks, the only names it may
     *         address without listing them.
     */
    public String modeFor(final String name) {
        final Peer peer = peer(name);
        if (peer == null) {
            return rules.project() != null ? rules.project() : ALLOW;
        }
        if (peer.mode() != null) {
            return peer.mode();
        }
        if (peer.conversation()) {
            return roomMode();
        }
        return rules.others() != null ? rules.others() : DENY;
    }

    /**
     * Returns how closely a message to the project's conversation is watched: to its people, or to one of them.
     *
     * @return The room's rule, else its default.
     */
    public String roomMode() {
        return rules.room() != null ? rules.room() : ALLOW;
    }

    private static String modes() {
        return String.join(", ", MODES.stream().sorted().toList());
    }

    /** The setting that makes the outgoing filter only report. */
    public static final String OUTGOING_FILTER = "outgoing_filter";

    /**
     * Constructor for a project whose outgoing filter refuses, the default.
     *
     * @param peers Who this project's tasks may address.
     * @param transports Each transport's settings, as written.
     */
    public Mail(List<Peer> peers, java.util.Map<String, Object> transports) {
        this(peers, transports, false, Rules.UNSAID);
    }

    /**
     * Constructor for a project that says nothing to any transport.
     *
     * @param peers Who this project's tasks may address.
     */
    public Mail(List<Peer> peers) {
        this(peers, java.util.Map.of());
    }

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
     * @param mode How closely a message to it is watched, one of {@link #MODES}, or {@code null} for its
     *        class's rule.
     */
    public record Peer(String name, String address, String trust, int perDay,
            @org.jspecify.annotations.Nullable String mode) {

        /**
         * Constructor for a peer whose class's rule decides how closely it is watched.
         *
         * @param name Peer name.
         * @param address Transport and its address.
         * @param trust Trust level.
         * @param perDay Messages a day, each way.
         */
        public Peer(String name, String address, String trust, int perDay) {
            this(name, address, trust, perDay, null);
        }

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

        // "<transport>:" with nothing after the colon: the project's own conversation on a transport that
        // makes one - its room, say - which nobody can name before the transport has made it.
        private static final Pattern CONVERSATION = Pattern.compile("[a-z0-9][a-z0-9-]*:");

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
            if (address == null || !ADDRESS.matcher(address).matches() && !CONVERSATION.matcher(address).matches()) {
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
            if (mode != null && !MODES.contains(mode)) {
                throw new ProjectException("A peer's 'mode' is one of " + modes() + ", got: " + mode);
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

        /**
         * Returns whether this peer is reached through the project's own conversation on its transport.
         *
         * @return true for {@code <transport>:} with nothing after the colon.
         */
        public boolean conversation() {
            return destination().isEmpty();
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
     * Returns the transports whose conversation this project has: every one configured under {@code transports},
     * and every one a peer's address names as {@code <scheme>:}.
     * <p>
     * Configuring the transport is the decision. A project with {@code matrix: {}} and no peer at {@code matrix:} had
     * no conversation, said nothing, and its tasks could not reach each other.
     *
     * @return Their schemes: the configured ones in the file's order, then any only a peer names.
     */
    public java.util.Set<String> conversations() {
        final java.util.Set<String> schemes = new java.util.LinkedHashSet<>(transports.keySet());
        peers.stream().filter(Peer::conversation).forEach(peer -> schemes.add(peer.transport()));
        return schemes;
    }

    /**
     * Returns the peer of one name.
     *
     * @param name As a message addresses it.
     * @return The peer, or {@code null} when this project may not address it.
     */
    public @org.jspecify.annotations.Nullable Peer peer(final String name) {
        final Peer written = peers.stream().filter(peer -> peer.name().equals(name)).findFirst().orElse(null);
        if (written != null || !PEOPLE.equals(name)) {
            return written;
        }
        return people();
    }

    /**
     * Returns the peer for the people in the project's conversation, unless the file names a peer {@link #PEOPLE}.
     * <p>
     * External, because a person's words are checked on the way in like anybody else's; reached through the first
     * conversation, so {@code mail.rules.room} decides how closely it is watched.
     *
     * @return It, or {@code null} for a project without a conversation or with a written peer of that name.
     */
    public @org.jspecify.annotations.Nullable Peer people() {
        if (peers.stream().anyMatch(peer -> peer.name().equals(PEOPLE)) || conversations().isEmpty()) {
            return null;
        }
        return new Peer(PEOPLE, conversations().iterator().next() + ":", Peer.EXTERNAL);
    }
}
