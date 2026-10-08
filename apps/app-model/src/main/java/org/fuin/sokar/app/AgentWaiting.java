package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.sokar.agent.api.Waiting;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Whether an agent is waiting for a person, read from outside - from the screen tmux draws in an attached
 * task and from the machine-readable output of an unattended run, both of which the host already has.
 * <p>
 * <strong>Derived, never observed, and never a guess.</strong> What waiting looks like is the agent's own
 * declaration ({@link Waiting}); nothing here knows any agent's wording. An agent that declares nothing is
 * reported as {@code UNDECLARED}, which is a different answer from {@code NOT_WAITING}: "this agent cannot
 * tell us" is honest, "not waiting" from an agent that was never able to say would be a lie with a
 * timestamp on it.
 * <p>
 * <strong>Pulled, never pushed.</strong> The host decides when to look - {@code tmux capture-pane} through
 * {@code podman exec}, a read of the state directory - and the container never decides when the host learns
 * something. Nothing here gives a task a way out.
 * <p>
 * <strong>Cheap enough for a listing read twice a second.</strong> Declarations are read from the installed
 * agents at most once a {@link #DECLARATIONS}, a screen at most once a {@link #SCREEN} per task, and a run's
 * log again only when it changed. One instance lives as long as the listing that uses it.
 */
public final class AgentWaiting {

    /** How often the installed agents' declarations are read again: an agent can be updated under a daemon. */
    static final Duration DECLARATIONS = Duration.ofMinutes(1);

    /** How often one task's screen is read. A capture is a {@code podman exec}, about 130 ms measured. */
    static final Duration SCREEN = Duration.ofSeconds(3);

    /**
     * How long a declaration may be read against a task's screen without ever matching before it is reported
     * as unproven: an agent's wording changes with its version, and a rule that stopped matching must not
     * read as an agent that never waits.
     */
    static final Duration UNPROVEN_AFTER = Duration.ofHours(24);

    /** What the screen says about waiting for a person. */
    public enum Screen {

        /** A rule the agent declares matched its screen. */
        WAITING,

        /** The agent declares rules, and none matched. */
        NOT_WAITING,

        /** The agent declares nothing for its screen, so nothing can be said. */
        UNDECLARED,

        /** Nothing here can see its screen: not attached, not running, or not its own screen right now. */
        UNSEEN
    }

    /** Whether a finished run's last message put something to the person. */
    public enum Asked {

        /** A rule the agent declares says it asked. */
        ASKED,

        /** A rule the agent declares says it did not. */
        DID_NOT_ASK,

        /** Nothing declared can tell - today every agent, since none can yet say it stopped to ask. */
        CANNOT_SAY
    }

    /**
     * What was derived for one task.
     *
     * @param screen What its screen says.
     * @param waitingFor What it waits for, when the matching rule says; "" otherwise.
     * @param unproven Whether its declaration has been read for long without ever matching.
     * @param lastMessage What its last run said last, "" when the agent does not say where that is or it
     *        said nothing yet. Observed: the record is in the run's own output.
     * @param asked Whether that was a question.
     * @param askedFrom Where that answer came from: "declared" for a rule the agent declares, "" for none.
     * @param session The session its agent was running, recorded so the next start continues it; "" when none
     *        is - and then the next start begins a fresh one.
     */
    public record Derived(Screen screen, String waitingFor, boolean unproven, String lastMessage, Asked asked,
            String askedFrom, String session) {

        /** What a task nothing could be read about carries. */
        public static final Derived NOTHING = new Derived(Screen.UNSEEN, "", false, "", Asked.CANNOT_SAY, "", "");
    }

    private record Declarations(Instant read, Map<String, Optional<Waiting>> byAgent) {
    }

    private record Captured(Instant at, Screen screen, String waitingFor) {
    }

    private record Watched(Instant since, boolean matched) {
    }

    private record LogRead(long size, Instant modified, String lastMessage) {
    }

    private final SokarContext context;

    private final Clock clock;

    private final java.util.function.Supplier<Map<String, Optional<Waiting>>> installed;

    private volatile @Nullable Declarations declarations;

    private final Map<String, Captured> screens = new ConcurrentHashMap<>();

    private final Map<String, Watched> watched = new ConcurrentHashMap<>();

    private final Map<String, LogRead> logs = new ConcurrentHashMap<>();

    /**
     * Constructor.
     *
     * @param context Where the agents, podman and the paths come from.
     * @param clock What "now" is.
     */
    AgentWaiting(SokarContext context, Clock clock) {
        this(context, clock, () -> installed(context));
    }

    /**
     * Constructor with where the declarations come from.
     *
     * @param context Where podman and the paths come from.
     * @param clock What "now" is.
     * @param installed Every installed agent's declaration by name, read again at most once a {@link #DECLARATIONS}.
     */
    AgentWaiting(SokarContext context, Clock clock,
            java.util.function.Supplier<Map<String, Optional<Waiting>>> installed) {
        this.context = context;
        this.clock = clock;
        this.installed = installed;
    }

    /**
     * Derives what can be said about one task.
     *
     * @param container The task's container.
     * @param running Whether it is up.
     * @param agent The agent it runs, or {@code null}.
     * @param mode How somebody is involved - {@code AGENT} for an attached agent - or {@code null}.
     * @param state The task's state directory.
     * @return What was derived.
     */
    Derived about(String container, boolean running, @Nullable String agent, @Nullable String mode, Path state) {
        final Waiting declared = agent == null ? null : declaration(agent);
        final Captured screen = screenOf(container, running, mode, declared, state);
        final boolean unproven = screen.screen() != Screen.UNDECLARED && unproven(container, screen);
        final String last = declared == null || declared.lastMessage() == null ? ""
                : lastMessage(container, state.resolve("task.log"), declared.lastMessage());
        return new Derived(screen.screen(), screen.waitingFor(), unproven, last, Asked.CANNOT_SAY, "",
                new TaskSession(context).recorded(container).orElse(""));
    }

    /** How many screens {@link #readScreens} reads at once. */
    static final int AT_ONCE = 8;

    /**
     * One task whose screen a listing will ask about.
     *
     * @param container The task's container.
     * @param running Whether it is up.
     * @param agent The agent it runs, or {@code null}.
     * @param mode How somebody is involved, or {@code null}.
     */
    record Wanted(String container, boolean running, @Nullable String agent, @Nullable String mode) {
    }

    /**
     * Reads the screens of several tasks at once, a few at a time, so that {@link #about} answers them from what was
     * read. Each read is a process in the container, about 100 ms; one after the other, a listing of 30 tasks took
     * about 4 s.
     *
     * @param tasks The tasks a listing is about to describe.
     */
    void readScreens(final List<Wanted> tasks) {
        final List<Wanted> due = tasks.stream().filter(task -> task.running() && "AGENT".equals(task.mode())
                && task.agent() != null).toList();
        if (due.size() < 2) {
            return;
        }
        // Platform threads, ended with the call: each waits on a process, which a virtual thread is not to do in this
        // daemon.
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(AT_ONCE, due.size()), Thread.ofPlatform().daemon().name("sokar-screen-", 0).factory())) {
            for (final Wanted task : due) {
                pool.submit(() -> screenOf(task.container(), true, task.mode(), declaration(
                        java.util.Objects.requireNonNull(task.agent())),
                        context.paths().tasks().containerState(task.container())));
            }
        }
    }

    private Captured screenOf(String container, boolean running, @Nullable String mode, @Nullable Waiting declared,
            Path state) {
        final Instant now = clock.instant();
        if (declared == null || declared.screen().isEmpty()) {
            return new Captured(now, Screen.UNDECLARED, "");
        }
        if (!running || !"AGENT".equals(mode)) {
            // Only an attached agent has a screen; an unattended run writes records, and a shell is a person's.
            screens.remove(container);
            return new Captured(now, Screen.UNSEEN, "");
        }
        final Captured before = screens.get(container);
        if (before != null && before.at().plus(SCREEN).isAfter(now)) {
            return before;
        }
        final Captured read = capture(container, declared, before, now, state);
        screens.put(container, read);
        return read;
    }

    private Captured capture(String container, Waiting declared, @Nullable Captured before, Instant now,
            Path state) {
        // What the task wrote, when its writer answers: a file, not a process in the container.
        final String written = ScreenFile.read(state, clock);
        if (written != null) {
            return written.isEmpty() ? new Captured(now, Screen.UNSEEN, "") : judged(declared.read(written), before, now);
        }
        final CommandResult drawn;
        try {
            drawn = context.podman().ask(container, Map.of(),
                    List.of("tmux", "capture-pane", "-p", "-J", "-t", org.fuin.sokar.runtime.Containerfile.SESSION));
        } catch (RuntimeException ex) {
            return new Captured(now, Screen.UNSEEN, "");
        }
        if (drawn.exitCode() != 0) {
            // No session yet, or one that ended: nothing is drawn to read.
            return new Captured(now, Screen.UNSEEN, "");
        }
        return judged(declared.read(drawn.standardOutput()), before, now);
    }

    private static Captured judged(final Waiting.Reading reading, final @Nullable Captured before, final Instant now) {
        return switch (reading.seen()) {
            case WAITING -> new Captured(now, Screen.WAITING, reading.waitingFor() == null ? "" : reading.waitingFor());
            case NOT_WAITING -> new Captured(now, Screen.NOT_WAITING, "");
            // A pager over the agent is still the agent's pane: keep what was last read of the agent itself.
            case NOT_ITS_SCREEN -> before == null ? new Captured(now, Screen.UNSEEN, "")
                    : new Captured(now, before.screen(), before.waitingFor());
        };
    }

    private boolean unproven(String container, Captured screen) {
        if (screen.screen() == Screen.UNSEEN) {
            return false;
        }
        final Watched seen = watched.merge(container, new Watched(screen.at(), screen.screen() == Screen.WAITING),
                (was, now) -> new Watched(was.since(), was.matched() || now.matched()));
        return !seen.matched() && !seen.since().plus(UNPROVEN_AFTER).isAfter(clock.instant());
    }

    private String lastMessage(String container, Path log, Waiting.LastMessage where) {
        try {
            if (!Files.isRegularFile(log)) {
                logs.remove(container);
                return "";
            }
            final long size = Files.size(log);
            final Instant modified = Files.getLastModifiedTime(log).toInstant();
            final LogRead before = logs.get(container);
            if (before != null && before.size() == size && before.modified().equals(modified)) {
                return before.lastMessage();
            }
            final List<Object> records = new ArrayList<>();
            for (final String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                try {
                    final Object record = Json.parse(line);
                    if (record != null) {
                        records.add(record);
                    }
                } catch (RuntimeException notARecord) {
                    // A line that is not a record - an error printed around the stream - says nothing here.
                }
            }
            final String said = where.of(records);
            final String last = said == null ? "" : said;
            logs.put(container, new LogRead(size, modified, last));
            return last;
        } catch (IOException ex) {
            return "";
        }
    }

    private @Nullable Waiting declaration(String agent) {
        Declarations known = declarations;
        final Instant now = clock.instant();
        if (known == null || !known.read().plus(DECLARATIONS).isAfter(now)) {
            final Map<String, Optional<Waiting>> byAgent = installed.get();
            known = new Declarations(now, byAgent);
            declarations = known;
        }
        return known.byAgent().getOrDefault(agent, Optional.empty()).orElse(null);
    }

    private static Map<String, Optional<Waiting>> installed(SokarContext context) {
        final Map<String, Optional<Waiting>> byAgent = new java.util.HashMap<>();
        try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
            for (final org.fuin.sokar.agent.api.InstalledAgent each : agents.all()) {
                byAgent.put(each.name(), Optional.ofNullable(each.definition().waiting()));
            }
        } catch (RuntimeException ex) {
            // An agent that will not describe itself declares nothing that can be read.
        }
        return byAgent;
    }

}
