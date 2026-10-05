package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.wire.Json;

/**
 * Wakes an agent at rest at its prompt when a message for it arrived: one fixed line typed into its terminal.
 * <p>
 * A person writes in the project's conversation and nothing else (the operator, 2026-10-04), so an agent waiting at
 * its prompt has to learn that a message for it is there. Typed only where its agent declares what its screen shows at
 * rest and shows it now, and never where a question to a person is open: the line and its Enter would answer it
 * (Agent Smith, 2026-10-04). The line is Sokar's own and the same every time; what was written reaches the agent only
 * as the file the host put into its inbox.
 */
public final class AgentWake {

    /** What is typed. */
    public static final String LINE = "A message for you waits in " + Mailbox.MOUNT + "/inbox/new - read it and answer"
            + " as " + Mailbox.GUIDE + " says.";

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    public AgentWake(final SokarContext context) {
        this.context = context;
    }

    /**
     * Says whether a message in a task's inbox is for that task's agent to act on: it names the task, or was said to
     * it in a direct chat. Every other message there is the room's conversation, read when the agent next looks.
     *
     * @param message The message, in the inbox.
     * @param container The task.
     * @param project Its project's name.
     * @return {@code true} when it names it.
     */
    static boolean namesIt(final Path message, final String container, final String project) {
        try {
            if (!(Json.parse(Files.readString(message, StandardCharsets.UTF_8)) instanceof Map<?, ?> document)
                    || !(document.get("metadata") instanceof Map<?, ?> metadata)) {
                return false;
            }
            if (Boolean.TRUE.equals(metadata.get("direct")) || "direct".equals(metadata.get("via"))) {
                return true;
            }
            if (metadata.get("inReplyToMessageId") != null || decided(document)) {
                // The filter's answer to the agent's own message: worth waking for only when it refused it or would
                // have, never for a receipt. Known by its decision too, because an id the detectors flagged is not
                // repeated in the answer.
                return refused(document);
            }
            final String task = org.fuin.sokar.runtime.ContainerName.taskIn(project, container);
            final Object to = metadata.get("to");
            final List<?> named = to instanceof List<?> list ? list : to == null ? List.of() : List.of(to);
            if (named.isEmpty() && PersonsWords.VIA_ROOM.equals(metadata.get("via"))) {
                // A person asking the room without naming anybody asks every task in it (the operator, walk 10:
                // "Wer ist da?" and nobody answered). A task's word to nobody stays the room's talk.
                return true;
            }
            return named.stream().map(String::valueOf).anyMatch(name -> name.equals(container) || name.equals(task));
        } catch (final IOException | RuntimeException ex) {
            return false;
        }
    }

    private static boolean decided(final Map<?, ?> document) {
        return document.get("parts") instanceof List<?> parts && parts.stream().anyMatch(part ->
                part instanceof Map<?, ?> each && each.get("data") instanceof Map<?, ?> data
                        && data.get("decision") != null);
    }

    private static boolean refused(final Map<?, ?> document) {
        if (document.get("parts") instanceof List<?> parts) {
            for (final Object part : parts) {
                if (part instanceof Map<?, ?> each && each.get("data") instanceof Map<?, ?> data
                        && ("rejected".equalsIgnoreCase(String.valueOf(data.get("decision")))
                                || Boolean.TRUE.equals(data.get("wouldReject")))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Beside a mailbox's record: messages its agent was told are there, one file name per line. */
    static final String ANNOUNCED = "wake-announced";

    /**
     * Tells a task's agent at rest about every message in its inbox that names it and it was not told about yet.
     * <p>
     * <strong>Tried at every pass until it lands or the message is read</strong> (Agent Smith, 2026-10-04): a word
     * that arrived while the agent worked found it busy, the one try was spent, and it went to rest with mail it was
     * never told of. Each message is announced once; one line covers all that wait.
     *
     * @param mailbox The task's mailbox.
     * @param container The task.
     * @param project Its project's name, or {@code null} when it is not known: then only a direct word counts.
     * @return Whether the line was typed.
     */
    public boolean announce(final Mailbox mailbox, final String container,
            final @org.jspecify.annotations.Nullable String project) {
        return announce(mailbox, container, project, () -> wake(container));
    }

    /**
     * Tells an agent about what waits, waking it the given way.
     *
     * @param mailbox The task's mailbox.
     * @param container The task.
     * @param project Its project's name, or {@code null}.
     * @param waking What wakes it, and says whether it did.
     * @return Whether it was woken.
     */
    boolean announce(final Mailbox mailbox, final String container,
            final @org.jspecify.annotations.Nullable String project, final java.util.function.BooleanSupplier waking) {
        try {
            // One at a time per task, across the daemon's passes and a person's 'talk tell' in another process: two
            // paths that found the same message at the same moment of rest typed it twice, and the second Enter broke
            // into the turn the first had started (Agent Smith, 2026-10-04).
            Files.createDirectories(mailbox.record());
            return FileLocks.holding(mailbox.record().resolve(".wake.lock"),
                    () -> announceHeld(mailbox, container, project, waking));
        } catch (final IOException | RuntimeException ex) {
            return false;
        }
    }

    /** After a line was typed, how long nothing more is typed into the task, whatever its screen still shows. */
    static final java.time.Duration QUIET = java.time.Duration.ofSeconds(10);

    /** Beside a mailbox's record: when its agent was last woken. */
    static final String LAST = "wake-last";

    private boolean announceHeld(final Mailbox mailbox, final String container,
            final @org.jspecify.annotations.Nullable String project, final java.util.function.BooleanSupplier waking) {
        try {
            // The screen still reads "at rest" for a moment after the line, until the agent starts its turn: what
            // arrives then is announced at a later pass, never typed into the turn.
            final Path last = mailbox.record().resolve(LAST);
            if (Files.isRegularFile(last) && Files.getLastModifiedTime(last).toInstant()
                    .isAfter(java.time.Instant.now().minus(QUIET))) {
                return false;
            }
            final java.util.Set<String> told = new java.util.HashSet<>();
            final Path record = mailbox.record().resolve(ANNOUNCED);
            if (Files.isRegularFile(record)) {
                told.addAll(Files.readAllLines(record, StandardCharsets.UTF_8));
            }
            final List<String> waiting = new java.util.ArrayList<>();
            if (Files.isDirectory(mailbox.inboxNew())) {
                try (java.util.stream.Stream<Path> listed = Files.list(mailbox.inboxNew())) {
                    listed.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .filter(path -> !told.contains(path.getFileName().toString()))
                            .filter(path -> namesIt(path, container, project == null ? "" : project))
                            .forEach(path -> waiting.add(path.getFileName().toString()));
                }
            }
            if (waiting.isEmpty() || !waking.getAsBoolean()) {
                return false;
            }
            Files.writeString(record, String.join("\n", waiting) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            Files.writeString(last, java.time.Instant.now() + "\n", StandardCharsets.UTF_8);
            return true;
        } catch (final IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Wakes a task's own agent, as its task's record names it, when it is at rest.
     * <p>
     * Never fails: an agent not woken finds the message in its inbox when it next looks.
     *
     * @param container The task.
     * @return Whether the line was typed.
     */
    public boolean wake(final String container) {
        try {
            final org.fuin.sokar.wire.TaskProfile profile =
                    org.fuin.sokar.wire.TaskProfile.readFrom(context.paths().tasks().containerState(container));
            final String agent = profile == null ? null : profile.agent();
            if (agent == null) {
                return false;
            }
            try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
                return agents.find(agent).map(found -> wake(container, found.definition())).orElse(false);
            }
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    /**
     * Types the line into a task's terminal when its agent is at rest there.
     *
     * @param container The task.
     * @param definition Its agent.
     * @return Whether it was typed.
     */
    public boolean wake(final String container, final AgentDefinition definition) {
        if (definition.atRest() == null) {
            return false;
        }
        final org.fuin.sokar.runtime.Podman.Screen screen = context.podman().screen(container, 20, false);
        if (!screen.live()) {
            return false;
        }
        final String shown = String.join("\n", screen.lines());
        if (definition.waiting() != null
                && definition.waiting().read(shown).seen() == org.fuin.sokar.agent.api.Waiting.Seen.WAITING) {
            return false;
        }
        return definition.atRest().matches(shown) && context.podman().type(container, LINE);
    }
}
