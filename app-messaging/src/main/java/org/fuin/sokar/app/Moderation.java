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
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.wire.Json;

/**
 * How closely a project's messages are watched, peer by peer, and whether a person holds one.
 * <p>
 * <strong>The mode is the project's, set once in its {@code project.yml}.</strong> Decided by the operator on
 * 2026-10-04: {@code mail.rules} gives a mode for the project's own tasks, its conversation and everyone else, and
 * a peer may name its own; {@link Mail#modeFor} says which applies. Until then each person set a mode per peer
 * here, on the host, and a project's tasks started at {@code prompt} with each other.
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
 * <strong>What a person still decides here is the brake</strong>: holding a peer, for every task of the project,
 * including tasks started later (the operator, 2026-09-29). It lives beside the mailboxes on the host, never inside
 * one: a task must not be able to change how closely it is watched.
 */
public final class Moderation {

    /** The modes a peer can be in, the same four {@code clearance} has. */
    public static final Set<String> MODES = Mail.MODES;

    /** What every peer is in where nothing says otherwise: a mailbox whose project is gone. */
    public static final String DEFAULT = Mail.PROMPT;

    /** What the file is called while it is being written, beside the file itself. */
    private static final String STAGED = ".tmp";


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

    private final @org.jspecify.annotations.Nullable Project project;

    /** The people in the project's conversation, by the names they joined with: each is watched as the room is. */
    private final java.util.Set<String> persons;

    /**
     * Constructor for a mailbox whose project is gone, where every peer waits for a person.
     *
     * @param file Where the holds are kept.
     */
    Moderation(final Path file) {
        this(file, null);
    }

    /**
     * Constructor.
     *
     * @param file Where the holds are kept.
     * @param project The project, whose file says each peer's mode; {@code null} when it is gone.
     */
    Moderation(final Path file, final @org.jspecify.annotations.Nullable Project project) {
        this(file, project, java.util.Set.of());
    }

    /**
     * Constructor for a project whose conversation people have joined.
     *
     * @param file Where the holds are kept.
     * @param project The project, whose file says each peer's mode; {@code null} when it is gone.
     * @param persons The people in its conversation, each reached in a direct chat.
     */
    Moderation(final Path file, final @org.jspecify.annotations.Nullable Project project,
            final java.util.Set<String> persons) {
        this.file = file;
        this.project = project;
        this.persons = java.util.Set.copyOf(persons);
    }

    /**
     * Returns how one project's peers are watched and held, its people included.
     *
     * @param context Where this user's Sokar keeps its files.
     * @param project The project, as its file says.
     * @return Them.
     */
    public static Moderation of(final SokarContext context, final Project project) {
        return new Moderation(context.paths().messaging().moderation(project.name()), project,
                Conversations.members(context, project).keySet());
    }

    /**
     * Returns how one project's peers are watched and held.
     *
     * @param paths Where this user's Sokar keeps its files.
     * @param project The project, as its file says.
     * @return Them.
     */
    public static Moderation of(final SokarPaths paths, final Project project) {
        return new Moderation(paths.messaging().moderation(project.name()), project);
    }

    /**
     * Returns the holds of a project nothing more is known about, where every peer waits for a person.
     *
     * @param paths Where this user's Sokar keeps its files.
     * @param project The project's name.
     * @return Them.
     */
    public static Moderation of(final SokarPaths paths, final String project) {
        return new Moderation(paths.messaging().moderation(project));
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
        final Peer held = read().get(name);
        final String mode;
        if (project == null) {
            mode = DEFAULT;
        } else if (persons.contains(name) && project.mail().peer(name) == null) {
            // A person, reached in a direct chat: the message leaves the machine as one to the room does.
            mode = project.mail().roomMode();
        } else {
            mode = project.mail().modeFor(name);
        }
        return new Peer(mode, held != null && held.held());
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
     * Holds or releases a peer, for every task of the project.
     *
     * @param name Peer name.
     * @param held Whether to hold everything for it, or {@code null} to leave that as it is.
     * @param mode Refused when given: a mode is set in the project's file.
     * @param project The project.
     * @return What the peer looks like now, or why the change was refused.
     * @throws IOException Reading or writing failed.
     */
    public Change set(final String name, final @org.jspecify.annotations.Nullable Boolean held,
            final @org.jspecify.annotations.Nullable String mode, final Project project)
            throws IOException {
        if (mode != null) {
            return new Change(null, "how closely " + name + " is watched is set in " + project.name()
                    + "'s project.yml, for every task of it: under 'mail." + Mail.RULES + "', or as 'mail.peers."
                    + name + ".mode'. Here a person only holds a peer or releases it.");
        }
        if (held != null) {
            final Map<String, Peer> peers = read();
            peers.put(name, new Peer(DEFAULT, held));
            write(peers);
        }
        // Asked of the project given here, which is the one whose file the person just read.
        return new Change(new Moderation(file, project, persons).peer(name), "");
    }

    /**
     * Returns every peer a person has held or released.
     *
     * @return Peer name to state, by name, each with the mode the project gives it.
     * @throws IOException Reading failed.
     */
    public Map<String, Peer> all() throws IOException {
        final Map<String, Peer> all = new TreeMap<>();
        for (final String name : read().keySet()) {
            all.put(name, peer(name));
        }
        return all;
    }

    private Map<String, Peer> read() throws IOException {
        final Map<String, Peer> peers = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return peers;
        }
        if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                && root.get("peers") instanceof Map<?, ?> named) {
            named.forEach((name, value) -> {
                // A mode a person set here before the project's file said it is not read: the file decides now.
                if (value instanceof Map<?, ?> state) {
                    peers.put(String.valueOf(name), new Peer(DEFAULT, Boolean.TRUE.equals(state.get("held"))));
                }
            });
        }
        return peers;
    }

    private void write(final Map<String, Peer> peers) throws IOException {
        final Map<String, Object> named = new TreeMap<>();
        peers.forEach((name, peer) -> named.put(name, new LinkedHashMap<>(Map.of("held", peer.held()))));
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("peers", named);
        Files.createDirectories(file.getParent());
        // Through a neighbour and a rename: a half-written file here would read as a peer nobody
        // decided about, which is the permissive answer for a project that already said otherwise.
        final Path staged = file.resolveSibling(file.getFileName() + STAGED);
        Files.writeString(staged, Json.write(root), StandardCharsets.UTF_8);
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
