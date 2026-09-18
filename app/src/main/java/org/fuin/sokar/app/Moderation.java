package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.wire.Json;

/**
 * What a person controls about a task's conversations: whether a peer is held, and how much is
 * asked before a message reaches it.
 * <p>
 * The four modes are {@code clearance}'s, with the same meanings, because an operator should not
 * have to learn a second vocabulary for the same decision:
 * <ul>
 * <li>{@code prompt} - a message waits in {@code hold/} until a person releases or refuses it.</li>
 * <li>{@code allow} - it goes out once the filter has accepted it.</li>
 * <li>{@code deny} - it never goes out, and the sender is told.</li>
 * <li>{@code off} - like {@code allow}. <strong>It does not switch the filter off</strong>: nothing
 * here can, because the filter is what stands between a task and everybody else.</li>
 * </ul>
 * <p>
 * <strong>{@code prompt} is the default, and in a project that is not {@code online} it is the only
 * mode available until the project says otherwise.</strong> {@code allow} and {@code off} mean work
 * nobody has read leaves the machine, which is the decision {@code unread_work_may_leave} exists to
 * carry - the same setting asked before unreviewed work goes to a review branch. A project that has
 * not said it gets a refusal naming the setting, rather than a mode that quietly does less than its
 * name says.
 * <p>
 * The state lives beside the mailbox on the host, never inside it: a task must not be able to
 * change how closely it is watched.
 */
public final class Moderation {

    /** The modes a peer can be in, the same four {@code clearance} has. */
    public static final Set<String> MODES = Set.of("prompt", "allow", "deny", "off");

    /** What a peer nobody has decided about is in. */
    public static final String DEFAULT = "prompt";

    /** The file, in the mailbox root and so outside what the task can see. */
    public static final String FILE = "moderation.json";

    /** The setting a project has to carry before a message may leave unread. */
    public static final String SETTING = "unread_work_may_leave";

    /**
     * What a person decided about one peer.
     *
     * @param mode One of {@link #MODES}.
     * @param held Whether everything for this peer waits, whatever the mode says.
     */
    public record Peer(String mode, boolean held) {
    }

    /**
     * What a change did.
     *
     * @param peer How the peer stands now, or {@code null} when nothing changed.
     * @param refused Why it was not made, or "".
     */
    public record Change(@org.jspecify.annotations.Nullable Peer peer, String refused) {
    }

    private final Path file;

    /**
     * Constructor.
     *
     * @param mailbox The task's mailbox.
     */
    public Moderation(final Mailbox mailbox) {
        this.file = mailbox.root().resolve(FILE);
    }

    /**
     * Returns how a peer stands.
     *
     * @param name Peer name.
     * @return Its mode and whether it is held. A peer nobody decided about is in {@link #DEFAULT}
     *         and not held.
     * @throws IOException Reading failed.
     */
    public Peer peer(final String name) throws IOException {
        final Map<String, Peer> peers = read();
        return peers.getOrDefault(name, new Peer(DEFAULT, false));
    }

    /**
     * Says whether a message for a peer may go out now.
     *
     * @param name Peer name.
     * @return An empty string when it may, otherwise why it waits, in words a sender can act on.
     * @throws IOException Reading failed.
     */
    public String whyNotNow(final String name) throws IOException {
        final Peer peer = peer(name);
        if (peer.held()) {
            return "everything for " + name + " is held until a person releases it";
        }
        return switch (peer.mode()) {
            case "allow", "off" -> "";
            case "deny" -> "messages to " + name + " are refused";
            default -> "it waits for a person to release it";
        };
    }

    /**
     * Says whether a refusal is final.
     *
     * @param name Peer name.
     * @return {@code true} when nothing a person does not do will change it, so the sender is told
     *         once rather than left waiting.
     * @throws IOException Reading failed.
     */
    public boolean refuses(final String name) throws IOException {
        return "deny".equals(peer(name).mode());
    }

    /**
     * Holds or releases a peer, or changes its mode.
     *
     * @param name Peer name.
     * @param held Whether to hold everything for it, or {@code null} to leave that as it is.
     * @param mode The new mode, or {@code null} to leave it as it is.
     * @param project The project, which decides whether unread work may leave at all.
     * @return What the peer looks like now, or why the change was refused.
     * @throws IOException Reading or writing failed.
     */
    public Change set(final String name, final @org.jspecify.annotations.Nullable Boolean held,
            final @org.jspecify.annotations.Nullable String mode, final Project project)
            throws IOException {
        if (mode != null && !MODES.contains(mode)) {
            return new Change(null,
                    "expected one of " + String.join(", ", MODES.stream().sorted().toList()));
        }
        if (mode != null && ("allow".equals(mode) || "off".equals(mode))
                && project.securityClass() != SecurityClass.ONLINE
                && !project.unreadWorkMayLeave()) {
            return new Change(null, "'" + mode + "' lets a message leave that nobody has read, and "
                    + project.name() + " is " + project.securityClass().name().toLowerCase()
                    + ". Set '" + SETTING + ": true' in the project to allow that.");
        }
        final Map<String, Peer> peers = read();
        final Peer was = peers.getOrDefault(name, new Peer(DEFAULT, false));
        final Peer now = new Peer(mode == null ? was.mode() : mode,
                held == null ? was.held() : held);
        peers.put(name, now);
        write(peers);
        return new Change(now, "");
    }

    /**
     * Returns every peer a person has decided about.
     *
     * @return Peer name to state, by name.
     * @throws IOException Reading failed.
     */
    public Map<String, Peer> all() throws IOException {
        return new TreeMap<>(read());
    }

    private Map<String, Peer> read() throws IOException {
        final Map<String, Peer> peers = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return peers;
        }
        if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                && root.get("peers") instanceof Map<?, ?> named) {
            named.forEach((name, value) -> {
                if (value instanceof Map<?, ?> state) {
                    final Object mode = state.get("mode");
                    peers.put(String.valueOf(name),
                            new Peer(mode instanceof String text && MODES.contains(text) ? text
                                    : DEFAULT, Boolean.TRUE.equals(state.get("held"))));
                }
            });
        }
        return peers;
    }

    private void write(final Map<String, Peer> peers) throws IOException {
        final Map<String, Object> named = new TreeMap<>();
        peers.forEach((name, peer) -> named.put(name,
                new LinkedHashMap<>(Map.of("mode", peer.mode(), "held", peer.held()))));
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("peers", named);
        Files.createDirectories(file.getParent());
        // Through a neighbour and a rename: a half-written file here would read as a peer nobody
        // decided about, which is the permissive answer for a project that already said otherwise.
        final Path staged = file.resolveSibling(FILE + ".tmp");
        Files.writeString(staged, Json.write(root), StandardCharsets.UTF_8);
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
