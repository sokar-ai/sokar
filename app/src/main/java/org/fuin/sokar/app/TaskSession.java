package org.fuin.sokar.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.SessionIds;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The session an agent was running in a task, so that starting the task again continues the conversation
 * rather than beginning a new one.
 * <p>
 * <strong>The task's, not the container's.</strong> Kept under the state directory, which survives a stop,
 * a restart and a reboot, and deleted with everything else when the task is removed - never in the runtime
 * directory, which a reboot wipes.
 * <p>
 * <strong>Taken from what the host already has</strong>, where the agent declares it is ({@link SessionIds}):
 * an unattended run's own records, or an attached agent's own session files, read by the host reaching into
 * the container. Nothing is given a way to send it.
 */
public final class TaskSession {

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context Where the paths and podman come from.
     */
    public TaskSession(SokarContext context) {
        this.context = context;
    }

    /**
     * Returns the session recorded for a task.
     *
     * @param container The task's container.
     * @return The id, or empty when none is recorded.
     */
    public Optional<String> recorded(String container) {
        final Path file = file(container);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            final String id = Files.readString(file, StandardCharsets.UTF_8).strip();
            return SessionIds.isId(id) ? Optional.of(id) : Optional.empty();
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /**
     * Records the session a task's agent is running.
     *
     * @param container The task's container.
     * @param id The session id.
     */
    public void record(String container, String id) {
        if (!SessionIds.isId(id)) {
            return;
        }
        final Path file = file(container);
        try {
            Files.createDirectories(file.getParent());
            final Path written = Files.writeString(file.resolveSibling(file.getFileName() + ".new"), id + "\n",
                    StandardCharsets.UTF_8);
            Files.move(written, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not record the session of " + container, ex);
        }
    }

    /**
     * Forgets a task's session, as removing the task does.
     *
     * @param container The task's container.
     */
    public void forget(String container) {
        try {
            Files.deleteIfExists(file(container));
        } catch (IOException ex) {
            throw new UncheckedIOException("could not forget the session of " + container, ex);
        }
    }

    /**
     * Reads the session an unattended run named in its own records.
     *
     * @param log The run's machine-readable output.
     * @param ids Where the agent declares the id is.
     * @return The id, or empty when the run named none there.
     */
    static Optional<String> fromRun(Path log, SessionIds ids) {
        if (!Files.isRegularFile(log)) {
            return Optional.empty();
        }
        final List<Object> records = new ArrayList<>();
        try {
            for (final String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                try {
                    final Object record = Json.parse(line);
                    if (record != null) {
                        records.add(record);
                    }
                } catch (RuntimeException notARecord) {
                    // A line that is not a record names no session.
                }
            }
        } catch (IOException ex) {
            return Optional.empty();
        }
        return Optional.ofNullable(ids.of(records));
    }

    /**
     * Reads the session an attached agent is keeping, from its own session files inside the running container.
     * <p>
     * The newest file ending in the declared suffix names it: the host looks, the container is told nothing.
     *
     * @param container The task's container, running.
     * @param ids Where the agent declares its session files are.
     * @return The id, or empty when there is no such file or the container cannot be asked.
     */
    Optional<String> fromFiles(String container, SessionIds ids) {
        if (ids.directory() == null || ids.suffix() == null) {
            return Optional.empty();
        }
        // Both words are refused at load unless they are plain path characters, so nothing here is quoting.
        final String script = "find \"$HOME/" + ids.directory() + "\" -type f -name '*" + ids.suffix()
                + "' -exec ls -t {} + 2>/dev/null | head -n 1";
        final CommandResult newest;
        try {
            newest = context.podman().ask(container, Map.of(), List.of("sh", "-c", script));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        final String path = newest.standardOutput().strip();
        if (newest.exitCode() != 0 || path.isEmpty()) {
            return Optional.empty();
        }
        final String name = path.substring(path.lastIndexOf('/') + 1);
        if (!name.endsWith(ids.suffix())) {
            return Optional.empty();
        }
        final String id = name.substring(0, name.length() - ids.suffix().length());
        return SessionIds.isId(id) ? Optional.of(id) : Optional.empty();
    }

    /**
     * Returns the session to continue, when the agent can continue one and one is recorded.
     *
     * @param container The task's container.
     * @param definition The task's agent.
     * @return The id, or empty for a fresh session.
     */
    Optional<String> toContinue(String container, @Nullable AgentDefinition definition) {
        if (definition == null || !definition.supportsResume() || definition.sessionIds() == null) {
            // Not guessed at: an agent that cannot name its sessions starts fresh, and says so.
            return Optional.empty();
        }
        return recorded(container);
    }

    /**
     * Records the session an attached agent is keeping, read from its files while the container still runs.
     * <p>
     * What stopping a task does before it stops the container: afterwards there is nothing running to ask.
     * Never fails the stop - a session that could not be read is one the next start does not continue, and
     * says so.
     *
     * @param container The task's container, running.
     */
    void rememberAttached(String container) {
        try {
            final org.fuin.sokar.wire.TaskProfile profile =
                    org.fuin.sokar.wire.TaskProfile.readFrom(context.paths().containerState(container));
            if (profile == null || profile.agent() == null
                    || profile.mode() != org.fuin.sokar.wire.TaskMode.AGENT) {
                return;
            }
            try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
                agents.find(profile.agent()).map(org.fuin.sokar.agent.api.InstalledAgent::definition)
                        .filter(AgentDefinition::supportsResume)
                        .map(AgentDefinition::sessionIds)
                        .flatMap(ids -> fromFiles(container, ids))
                        .ifPresent(id -> record(container, id));
            }
        } catch (RuntimeException ex) {
            // Reported by the start that cannot continue, not by the stop that could not read.
        }
    }

    /**
     * Returns what starts an attached task's agent again when its session is gone - continuing the session it
     * was keeping, where the agent can - or {@code null} to join the session that is there, or for a task
     * whose agent is not the point.
     * <p>
     * Only when there is no session: a live one already has its agent, and a second would be two
     * conversations on one screen. Says which of the two it is, so an interface or a person never assumes a
     * continuation that did not happen.
     *
     * @param container The task's container, running.
     * @param out Where the choice is said.
     * @return A shell command, or {@code null}.
     */
    public @Nullable String attachedAgent(String container, java.io.PrintWriter out) {
        final org.fuin.sokar.wire.TaskProfile profile =
                org.fuin.sokar.wire.TaskProfile.readFrom(context.paths().containerState(container));
        if (profile == null || profile.agent() == null || profile.mode() != org.fuin.sokar.wire.TaskMode.AGENT) {
            return null;
        }
        try {
            if (context.podman().ask(container, Map.of(), List.of("tmux", "has-session", "-t",
                    org.fuin.sokar.runtime.Containerfile.SESSION)).exitCode() == 0) {
                return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        final AgentDefinition definition;
        try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
            definition = agents.find(profile.agent()).map(org.fuin.sokar.agent.api.InstalledAgent::definition)
                    .orElse(null);
        }
        if (definition == null) {
            return null;
        }
        // The files first: they are what the agent itself kept, and a stop that could not read them left
        // nothing recorded.
        final SessionIds ids = definition.supportsResume() ? definition.sessionIds() : null;
        if (ids != null) {
            fromFiles(container, ids).ifPresent(id -> record(container, id));
        }
        final String id = toContinue(container, definition).orElse(null);
        final List<String> command = new ArrayList<>(definition.sandboxedCommand());
        if (id != null) {
            command.add(java.util.Objects.requireNonNull(definition.resumeFlag()));
            command.add(id);
        }
        out.println("session   " + (id != null ? "continuing " + id
                : ids == null ? "a fresh one - " + definition.name() + " does not say how to continue one"
                : "a fresh one - none was recorded"));
        out.flush();
        return org.fuin.sokar.runtime.ShellWords.quote(command);
    }

    private Path file(String container) {
        return context.paths().sessionRecord(container);
    }

}
