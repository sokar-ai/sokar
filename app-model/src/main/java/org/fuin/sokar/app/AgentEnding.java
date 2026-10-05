package org.fuin.sokar.app;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.sokar.agent.api.AgentEnd;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.runtime.Podman;
import org.jspecify.annotations.Nullable;

/**
 * Says how a task's agent ended its run, when it has.
 * <p>
 * A provider answered 402 and the agent ended; the task said "idle", then "working", and nothing pointed the person at
 * what had happened. The agent reads its own end (each ends its output its own way); this asks it about the last lines
 * of the task's log, and adds what the workspace holds that never reached the gate - an agent can finish its report and
 * end before it pushes.
 * <p>
 * <strong>Asked only of a quiet log, and once per size.</strong> A log still growing is a run still going, and asking
 * on every pass would start every agent twice a second for every running task.
 */
public final class AgentEnding {

    /** How long a log must have been still before its end is asked for. */
    static final Duration QUIET = Duration.ofSeconds(10);

    /** How much of a log's end is read for its last lines. */
    static final int TAIL = 64 * 1024;

    /** How many lines from the end are offered to the agent. */
    static final int LINES = 40;

    /**
     * Asks an agent which of its last lines is the end of its run.
     */
    @FunctionalInterface
    public interface Reader {

        /**
         * Reads lines for an end.
         *
         * @param agent The agent's name.
         * @param fromEnd Lines of its output, the last first.
         * @return How its run ended, or {@code null} when none of them is its end.
         */
        @Nullable AgentEnd ended(String agent, List<String> fromEnd);
    }

    /**
     * How a task's agent ended.
     *
     * @param at When its log was last written, ISO 8601.
     * @param finished Whether it answered its prompt to the end.
     * @param error Empty when finished; else the text as the agent or its provider gave it.
     * @param source {@code provider} or {@code agent}.
     * @param status The provider's HTTP status, when the provider refused.
     * @param unpushed Changed files and commits in the workspace that never reached the gate.
     */
    public record Ended(String at, boolean finished, String error, String source, @Nullable Integer status,
            int unpushed) {

        /**
         * Returns it as an interface reads it.
         *
         * @return The fields; the status only when there is one.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("at", at);
            map.put("finished", finished);
            map.put("error", error);
            map.put("source", source);
            if (status != null) {
                map.put("status", status);
            }
            map.put("unpushed", unpushed);
            return map;
        }
    }

    private record Seen(long size, @Nullable Ended ended) {
    }

    private final Podman podman;

    private final Clock clock;

    private final Reader reader;

    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    /**
     * Creates it.
     *
     * @param runner Runs podman, for the workspace's census.
     * @param clock The time.
     * @param reader Asks an agent about its lines.
     */
    public AgentEnding(CommandRunner runner, Clock clock, Reader reader) {
        this.podman = new Podman(runner);
        this.clock = clock;
        this.reader = reader;
    }

    /**
     * Creates it for a machine, asking its installed agents.
     *
     * @param context The machine.
     * @return The reading.
     */
    public static AgentEnding of(SokarContext context) {
        return new AgentEnding(context.runner(), Clock.systemUTC(), (agent, fromEnd) -> {
            try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
                final org.fuin.sokar.agent.api.InstalledAgent installed = agents.find(agent).orElse(null);
                if (installed == null) {
                    return null;
                }
                for (final String line : fromEnd) {
                    final AgentEnd ended = installed.ended(line);
                    if (ended != null) {
                        return ended;
                    }
                }
                return null;
            } catch (RuntimeException ex) {
                // An agent that cannot be asked says nothing about its end, as before.
                return null;
            }
        });
    }

    /**
     * Says how a task's agent ended, if it has.
     *
     * @param container The task.
     * @param running Whether its container is up: the workspace is asked only then.
     * @param agent Its agent's name, or {@code null} when it has none.
     * @param state Its state directory, holding {@code task.log}.
     * @return How it ended, or {@code null} while it runs, or when its agent does not say.
     */
    public @Nullable Ended about(String container, boolean running, @Nullable String agent, Path state) {
        final Path log = state.resolve("task.log");
        if (agent == null || !Files.isRegularFile(log)) {
            return null;
        }
        final long size;
        final java.time.Instant written;
        try {
            size = Files.size(log);
            written = Files.getLastModifiedTime(log).toInstant();
        } catch (IOException ex) {
            return null;
        }
        if (written.isAfter(clock.instant().minus(QUIET))) {
            return null;
        }
        final Seen known = seen.get(container);
        if (known != null && known.size() == size) {
            return known.ended();
        }
        final AgentEnd end = reader.ended(agent, lastLines(log, size));
        final Ended ended = end == null ? null : new Ended(written.toString(), end.finished(), end.error(),
                end.source(), end.status(), unpushed(container, running, state));
        seen.put(container, new Seen(size, ended));
        return ended;
    }

    private int unpushed(String container, boolean running, Path state) {
        // A stopped task is not asked: what it held was written down when it stopped.
        final UnhandedWork.Held held = running ? UnhandedWork.ask(podman, container) : UnhandedWork.read(state);
        return held.readable() ? held.changedFiles() + held.unpushedCommits() : 0;
    }

    private static List<String> lastLines(Path log, long size) {
        final int length = (int) Math.min(size, TAIL);
        final byte[] tail = new byte[length];
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            file.seek(size - length);
            file.readFully(tail);
        } catch (IOException ex) {
            return List.of();
        }
        final String[] lines = new String(tail, StandardCharsets.UTF_8).split("\n");
        final List<String> fromEnd = new ArrayList<>();
        // The first line of a cut read may be the end of a longer one: only whole lines are offered.
        final int first = size > length ? 1 : 0;
        for (int i = lines.length - 1; i >= first && fromEnd.size() < LINES; i--) {
            if (!lines[i].isBlank()) {
                fromEnd.add(lines[i]);
            }
        }
        return fromEnd;
    }
}
