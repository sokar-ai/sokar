package org.fuin.sokar.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.runtime.ContainerSummary;
import org.fuin.sokar.wire.Sidecar;
import org.jspecify.annotations.Nullable;

/**
 * What tasks exist on this machine, as data rather than as a printed table.
 * <p>
 * The CLI renders this and the daemon serializes it, so that "what tasks are there" is answered in
 * one place. The interface and the CLI have to reach identical behavior through the same calls;
 * two implementations of the same question are how a feature comes to exist in one and not the
 * other, and how they come to disagree about something an operator is reading to decide what to
 * stop.
 * <p>
 * The container runtime knows the containers; only Sokar knows which project and security class
 * each belongs to. Both come from the task's own state directory rather than from the container
 * name, because a project name may contain the separator that name is built with.
 */
public final class TaskInventory {

    /**
     * One task, as much as this machine can say about it.
     *
     * @param name Container name, which is what every other command takes.
     * @param project Project the task belongs to, or {@code null} when nothing recorded it.
     * @param securityClass The project's class, or {@code null} when nothing recorded it.
     * @param state The runtime's own words, such as {@code Up 4 minutes}.
     * @param running Whether the runtime says it is up.
     * @param helpers How many recorded helper processes are alive.
     * @param agent Agent running in it, or {@code null} when it has none or nothing recorded one.
     * @param mode How somebody is meant to be involved, or {@code null} when nothing recorded it.
     * @param prompt What an unattended task was asked to do, or {@code null}.
     * @param branch Ref its work goes to, or {@code null} when it has no gate.
     * @param since When its current state began, ISO-8601, or empty when the runtime cannot say.
     * @param activity What the work is doing, as against what the container is doing.
     * @param waitingFor What it is waiting to be told, when it is waiting.
     * @param clearance What it does with a blocked connection, or {@code null} when nothing
     *        recorded it. {@code off} means nothing asks and nothing is refused, which is the one
     *        state that has to be visible wherever the task is listed.
     * @param label A caption somebody gave it, or {@code null}.
     * @param waiting 1 when its own work is waiting at the gate, 0 otherwise.
     * @param startAction What Start would do to it: RUNNING, RESUME or PREDATES_RESTART.
     * @param startDetail Why, when startAction is a refusal; empty otherwise.
     * @param repository The repository it works on, or {@code null}.
     * @param commit What its configuration was verified at, or {@code null}.
     * @param derived Whether it waits for a person, derived from its agent's own declaration - never observed.
     * @param credentials What it was given beyond its agent's own credential: entry to destination.
     * @param grants For each credential a person granted: {@code grantedBy} and {@code grantedAt}.
     * @param builds Which reader follows the builds of its pushes, and what they did, the newest push first.
     */
    public record Task(String name, @Nullable String project, @Nullable String securityClass,
            String state, boolean running, long helpers, @Nullable String agent,
            @Nullable String mode, @Nullable String prompt, @Nullable String branch,
            String since, Activity activity, @Nullable String waitingFor,
            @Nullable String clearance, @Nullable String label, int waiting,
            String startAction, String startDetail, @Nullable String repository,
            @Nullable String commit, AgentWaiting.Derived derived, Map<String, String> credentials,
            Map<String, Map<String, String>> grants, @Nullable String provider,
            AgentEnding.@Nullable Ended agentEnded, HandIns.Summary handIns, TaskBuilds.View builds) {

        /**
         * Constructor for a task that names no provider, the shape before it was recorded.
         *
         * @param name Container name.
         * @param project Project, or {@code null}.
         * @param securityClass Class, or {@code null}.
         * @param state Container state.
         * @param running Whether it is up.
         * @param helpers How many host processes it has.
         * @param agent Agent, or {@code null}.
         * @param mode Mode, or {@code null}.
         * @param prompt Prompt, or {@code null}.
         * @param branch Branch, or {@code null}.
         * @param since Since when.
         * @param activity What its work is doing.
         * @param waitingFor What it is waiting for, or {@code null}.
         * @param clearance Its clearance mode, or {@code null}.
         * @param label Its label, or {@code null}.
         * @param waiting Whether its work is waiting for review.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         * @param repository Repository, or {@code null}.
         * @param commit Commit, or {@code null}.
         * @param derived What its screen and messages say.
         * @param credentials What it was given beyond its agent's own credential.
         * @param grants What it acts as.
         */
        public Task(String name, @Nullable String project, @Nullable String securityClass, String state,
                boolean running, long helpers, @Nullable String agent, @Nullable String mode, @Nullable String prompt,
                @Nullable String branch, String since, Activity activity, @Nullable String waitingFor,
                @Nullable String clearance, @Nullable String label, int waiting, String startAction,
                String startDetail, @Nullable String repository, @Nullable String commit,
                AgentWaiting.Derived derived, Map<String, String> credentials, Map<String, Map<String, String>> grants) {
            this(name, project, securityClass, state, running, helpers, agent, mode, prompt, branch, since, activity,
                    waitingFor, clearance, label, waiting, startAction, startDetail, repository, commit, derived,
                    credentials, grants, null, null, HandIns.Summary.none(), TaskBuilds.View.none());
        }

        /**
         * Constructor for a task that acts as nobody, the shape before grants were recorded.
         *
         * @param name Container name.
         * @param project Project, or {@code null}.
         * @param securityClass Class, or {@code null}.
         * @param state Container state.
         * @param running Whether it is up.
         * @param helpers How many host processes it has.
         * @param agent Agent, or {@code null}.
         * @param mode Mode, or {@code null}.
         * @param prompt Prompt, or {@code null}.
         * @param branch Branch, or {@code null}.
         * @param since Since when.
         * @param activity What its work is doing.
         * @param waitingFor What it is waiting for, or {@code null}.
         * @param clearance Its clearance mode, or {@code null}.
         * @param label Its label, or {@code null}.
         * @param waiting Whether its work is waiting for review.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         * @param repository Repository, or {@code null}.
         * @param commit Commit, or {@code null}.
         * @param derived What its screen and messages say.
         * @param credentials What it was given beyond its agent's own credential.
         */
        public Task(String name, @Nullable String project, @Nullable String securityClass, String state,
                boolean running, long helpers, @Nullable String agent, @Nullable String mode, @Nullable String prompt,
                @Nullable String branch, String since, Activity activity, @Nullable String waitingFor,
                @Nullable String clearance, @Nullable String label, int waiting, String startAction,
                String startDetail, @Nullable String repository, @Nullable String commit,
                AgentWaiting.Derived derived, Map<String, String> credentials) {
            this(name, project, securityClass, state, running, helpers, agent, mode, prompt, branch, since, activity,
                    waitingFor, clearance, label, waiting, startAction, startDetail, repository, commit, derived,
                    credentials, Map.of());
        }

        /**
         * Constructor for a task given no credential beyond its agent's own, the shape before a task held
         * more than one.
         *
         * @param name Container name.
         * @param project Project, or {@code null}.
         * @param securityClass Class, or {@code null}.
         * @param state Container state.
         * @param running Whether it runs.
         * @param helpers Helper processes.
         * @param agent Agent, or {@code null}.
         * @param mode Mode, or {@code null}.
         * @param prompt Prompt, or {@code null}.
         * @param branch Branch, or {@code null}.
         * @param since Since when.
         * @param activity What its work is doing.
         * @param waitingFor What it waits for, or {@code null}.
         * @param clearance Clearance, or {@code null}.
         * @param label Label, or {@code null}.
         * @param waiting Pending pushes.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         * @param repository Repository, or {@code null}.
         * @param commit Commit, or {@code null}.
         * @param derived What its screen and messages say.
         */
        public Task(String name, @Nullable String project, @Nullable String securityClass, String state,
                boolean running, long helpers, @Nullable String agent, @Nullable String mode, @Nullable String prompt,
                @Nullable String branch, String since, Activity activity, @Nullable String waitingFor,
                @Nullable String clearance, @Nullable String label, int waiting, String startAction,
                String startDetail, @Nullable String repository, @Nullable String commit,
                AgentWaiting.Derived derived) {
            this(name, project, securityClass, state, running, helpers, agent, mode, prompt, branch, since, activity,
                    waitingFor, clearance, label, waiting, startAction, startDetail, repository, commit, derived,
                    Map.of(), Map.of());
        }

        /**
         * Constructor for a task nothing could be derived about.
         *
         * @param name Container name.
         * @param project Project name, or {@code null}.
         * @param securityClass Security class, or {@code null}.
         * @param state The runtime's own words.
         * @param running Whether it is up.
         * @param helpers How many host processes it has.
         * @param agent The agent, or {@code null}.
         * @param mode How somebody is involved, or {@code null}.
         * @param prompt What it was asked, or {@code null}.
         * @param branch The ref it pushes to, or {@code null}.
         * @param since When it entered its state.
         * @param activity What its work is doing.
         * @param waitingFor What it is waiting for, or {@code null}.
         * @param clearance Its clearance mode, or {@code null}.
         * @param label Its label, or {@code null}.
         * @param waiting Whether its work is waiting for review.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         * @param repository The repository, or {@code null}.
         * @param commit The verified commit, or {@code null}.
         */
        public Task(String name, @Nullable String project, @Nullable String securityClass,
                String state, boolean running, long helpers, @Nullable String agent,
                @Nullable String mode, @Nullable String prompt, @Nullable String branch,
                String since, Activity activity, @Nullable String waitingFor,
                @Nullable String clearance, @Nullable String label, int waiting,
                String startAction, String startDetail, @Nullable String repository,
                @Nullable String commit) {
            this(name, project, securityClass, state, running, helpers, agent, mode, prompt, branch, since,
                    activity, waitingFor, clearance, label, waiting, startAction, startDetail, repository, commit,
                    AgentWaiting.Derived.NOTHING);
        }

        /**
         * Constructor for a task taken before the repository was known.
         *
         * @param name Container name.
         * @param project Project name, or {@code null}.
         * @param securityClass Security class, or {@code null}.
         * @param state The runtime's own words.
         * @param running Whether it is up.
         * @param helpers How many host processes it has.
         * @param agent The agent, or {@code null}.
         * @param mode How somebody is involved, or {@code null}.
         * @param prompt What it was asked, or {@code null}.
         * @param branch The ref it pushes to, or {@code null}.
         * @param since When it entered its state.
         * @param activity What its work is doing.
         * @param waitingFor What it is waiting for, or {@code null}.
         * @param clearance Its clearance mode, or {@code null}.
         * @param label Its label, or {@code null}.
         * @param waiting Whether its work is waiting for review.
         * @param startAction What starting it would do.
         * @param startDetail Why.
         */
        public Task(String name, @Nullable String project, @Nullable String securityClass,
                String state, boolean running, long helpers, @Nullable String agent,
                @Nullable String mode, @Nullable String prompt, @Nullable String branch,
                String since, Activity activity, @Nullable String waitingFor,
                @Nullable String clearance, @Nullable String label, int waiting,
                String startAction, String startDetail) {
            this(name, project, securityClass, state, running, helpers, agent, mode, prompt,
                    branch, since, activity, waitingFor, clearance, label, waiting, startAction,
                    startDetail, null, null);
        }

        /**
         * Returns this task as plain values, for a caller that has to put it on a wire.
         *
         * @return The task, with {@code null} replaced by an empty string so the map is
         *         representable in every encoding a client might use.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("project", project == null ? "" : project);
            map.put("securityClass", securityClass == null ? "" : securityClass);
            // "" for a container created before the repository was labelled, which is a project's
            // own repository and the only one there was. An interface renders the empty string as
            // the project's own rather than as "unknown".
            map.put("repository", repository == null ? "" : repository);
            // What the task's configuration was verified at. "" when nothing verified it, which
            // is every task of a project this machine does not follow.
            map.put("commit", commit == null ? "" : commit);
            map.put("state", state);
            map.put("running", running);
            map.put("helpers", helpers);
            map.put("agent", agent == null ? "" : agent);
            map.put("provider", provider == null ? "" : provider);
            map.put("mode", mode == null ? "" : mode);
            map.put("prompt", prompt == null ? "" : prompt);
            map.put("branch", branch == null ? "" : branch);
            map.put("since", since);
            map.put("activity", activity.name());
            if (agentEnded != null) {
                map.put("agentEnded", agentEnded.asMap());
            }
            map.put("waitingFor", waitingFor == null ? "" : waitingFor);
            map.put("clearance", clearance == null ? "" : clearance);
            // Beside the name, never instead of it: the name is what every other call takes and
            // what somebody types at the machine. Empty means the row shows its real name.
            map.put("label", label == null ? "" : label);
            // Whether this task's own work is waiting at the gate. Answered here rather than left
            // as a join: Task.name is a CONTAINER name and PendingPush.name is a TASK name, and
            // several containers over time share one ref - so nothing a client lined up could be
            // right for more than one of them.
            map.put("waiting", waiting);

            // Carried rather than left to the caller: 'name' is a container name, and the rule
            // relating the two belongs here. It changed in this release, which is exactly why an
            // interface must not cut a prefix off the name to get it.
            final String within = project == null ? ""
                    : org.fuin.sokar.runtime.ContainerName.taskIn(project, name);
            map.put("task", within);

            // What Start would do to this task, answered before anybody presses it. A listed task
            // exists by definition, so CREATE cannot appear here. Worked out where the state
            // directory is at hand; PREDATES_RESTART comes from there.
            //
            // Two values this does NOT yet produce, and both are absences rather than oversights:
            //
            //   SUPERSEDED_NAME  a container from before one-container-per-task cannot be told
            //                    apart from a task whose name genuinely ends in digits, and
            //                    guessing would refuse to start a task that is perfectly fine.
            //   NEEDS_VAULT      nothing here knows whether the vault is locked, and asking per
            //                    row would make a listing of forty tasks forty questions.
            //
            // A client renders a value it does not know rather than failing on it, so both can
            // start appearing without breaking anything.
            map.put("startAction", startAction);
            map.put("startDetail", startDetail);

            // Whether it waits for a person, as its agent's own declaration reads its output. Derived, and
            // kept apart from 'activity' and 'waitingFor', which are what the runtime and the clearance
            // watcher observed: an interface renders these as a reading, never as a fact.
            map.put("screen", derived.screen().name());
            map.put("screenWaitingFor", derived.waitingFor());
            map.put("screenUnproven", derived.unproven());
            map.put("lastMessage", derived.lastMessage());
            map.put("asked", derived.asked().name());
            map.put("askedFrom", derived.askedFrom());
            // The session the next start continues, so an interface can say so before it is pressed.
            map.put("session", derived.session());
            // What it was given beyond its agent's own credential, by name and destination - never a token.
            map.put("credentials", credentials);
            // Whom it acts as, for each of those a person granted: an audit fact, never the grant itself.
            map.put("grants", grants);

            // The container this task is, so a record entry of a name another task carried before can be told
            // apart; "" for a task with none recorded.
            map.put("run", handIns.run());
            // The bytes one file handed to it may have, fixed when it started: a client refuses a larger file
            // before reading it.
            map.put("handInLimit", handIns.limit());
            // What is in /sokar/files now, as it was handed in by this run.
            map.put("files", handIns.files().stream().map(HandIns.HandedFile::asMap).toList());
            // What the builds of its pushes did, the newest push first: the verdict a person sees without opening it.
            map.put("builds", builds.builds().stream().map(TaskBuilds.Seen::asMap).toList());
            // Which forge follows them, so an empty list reads as "no push yet" rather than as "nothing follows".
            map.put("buildReader", builds.reader());
            // Why a named reader does not follow them; "" while it does.
            map.put("buildProblem", builds.problem());

            // Nothing records a phase yet. "" is the honest answer for a task that is in none,
            // and it is what every task answers until a detached Start has something to report.
            map.put("phase", "");
            return map;
        }
    }

    /**
     * What the work in a task is doing, as against what its container is doing.
     * <p>
     * {@code running} answers the container's question. This answers the one an operator actually
     * has, and the two are not the same: a task blocked on a question nobody saw and a task
     * grinding through a build are both running.
     */
    public enum Activity {

        /** Its container is not up. Stopped, finished or dead - all three are gone. */
        DEAD,

        /** Waiting for a person to answer something. Said by the thing that asked, not guessed. */
        WAITING,

        /** Producing output. */
        WORKING,

        /** Up, producing nothing, and not waiting for anybody as far as anything can tell. */
        IDLE,

        /**
         * Up, and its agent's run has ended - finished, or stopped by an error its agent read from its own output.
         * <p>
         * Neither working nor idle: a run that ended on a provider's refusal said "idle", then "working", while nothing
         * would happen any more.
         */
        ENDED,

        /**
         * Up, and nothing here can see what it is doing.
         * <p>
         * A task somebody attached a terminal to writes its work to that terminal, not to a file
         * this can read. Reported as its own value rather than as idle: a state that is silently
         * wrong is worse than one that says it does not know.
         */
        UNKNOWN
    }

    /** How long a task's own log may be quiet before the work is called idle rather than busy. */
    private static final java.time.Duration QUIET = java.time.Duration.ofSeconds(60);

    /**
     * What a task the machine's restart took down says beside {@code RESUME}: not a refusal, but the reason it is
     * down, which a person should not have to read out of a boot log.
     */
    public static final String RESTARTED = "the machine restarted; starting it brings it back whole";

    private final SokarContext context;

    private final @Nullable AgentWaiting agentWaiting;

    private final @Nullable AgentEnding ending;

    /**
     * Constructor for a listing that derives nothing about waiting for a person.
     * <p>
     * What most callers want: deriving starts the installed agents and reads screens, and a command that
     * only needs names and states must not pay for that.
     *
     * @param context Where podman and the paths come from.
     */
    public TaskInventory(SokarContext context) {
        this(context, null);
    }

    /**
     * Constructor for a listing that says whether each task waits for a person.
     * <p>
     * Keep it for as long as the listing is read again - a watch - so what it derives is read at the pace
     * that is cheap rather than on every call.
     *
     * @param context Where podman and the paths come from.
     * @return The inventory.
     */
    public static TaskInventory deriving(SokarContext context) {
        return new TaskInventory(context, new AgentWaiting(context, java.time.Clock.systemUTC()),
                AgentEnding.of(context));
    }

    /**
     * Constructor with the reader of what an agent waits for.
     *
     * @param context Where podman and the paths come from.
     * @param agentWaiting What derives whether a task waits for a person, or {@code null} for nothing.
     */
    TaskInventory(SokarContext context, @Nullable AgentWaiting agentWaiting) {
        this(context, agentWaiting, null);
    }

    TaskInventory(SokarContext context, @Nullable AgentWaiting agentWaiting, @Nullable AgentEnding ending) {
        this.context = context;
        this.agentWaiting = agentWaiting;
        this.ending = ending;
    }

    /**
     * Returns the project a task records on its container.
     * <p>
     * What a command about one task defaults to when no project is named: asking for it again would
     * be asking somebody to repeat what the machine already knows.
     *
     * @param container The task's container name.
     * @return The project, or {@code null} when there is no such task or it records none.
     */
    public @Nullable String projectOf(String container) {
        return tasks().stream().filter(task -> task.name().equals(container)).map(Task::project)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /**
     * Returns every task on this machine, running or stopped.
     *
     * @return Tasks, in the order the runtime lists them.
     */
    public List<Task> tasks() {
        // The refs waiting in each project's mirror, asked once per project rather than once per
        // task: this list is read on every change, and a git call per task would make it cost
        // what a listing must not.
        final Map<String, java.util.Set<String>> waiting = new java.util.HashMap<>();
        final List<ContainerSummary> summaries = context.podman().sokarTasks();
        if (agentWaiting != null) {
            // The screens first, several at once; describing each task then reads what was read.
            agentWaiting.readScreens(summaries.stream().filter(ContainerSummary::running).map(summary -> {
                final org.fuin.sokar.wire.TaskProfile profile = org.fuin.sokar.wire.TaskProfile.readFrom(
                        context.paths().tasks().containerState(summary.name()));
                return new AgentWaiting.Wanted(summary.name(), true, profile == null ? null : profile.agent(),
                        profile == null ? null : profile.mode().name());
            }).toList());
        }
        return summaries.stream().map(summary -> describe(summary, waiting)).toList();
    }

    /**
     * Returns the refs waiting for review in one project's mirror, reading each mirror once.
     *
     * @param cache Filled as projects are seen.
     * @param project Project name, or {@code null} when nothing recorded one.
     * @return Incoming ref names, without the namespace prefix.
     */
    private java.util.Set<String> waitingIn(Map<String, java.util.Set<String>> cache,
            @Nullable String project) {
        if (project == null) {
            return java.util.Set.of();
        }
        return cache.computeIfAbsent(project, name -> {
            final Path mirror = context.paths().xdg().data()
                    .resolve("mirrors").resolve(name + ".git");
            if (!java.nio.file.Files.isDirectory(mirror)) {
                return java.util.Set.of();
            }
            try {
                return java.util.Set.copyOf(new org.fuin.sokar.gate.GitGate(context.runner(),
                        mirror, org.fuin.sokar.gate.GateMode.GATEKEEPING, null).pending());
            } catch (RuntimeException ex) {
                // A directory that is not a repository, or a git that would not run. Neither is
                // worth failing a listing for, and a task that cannot be asked reads as none
                // waiting rather than as broken.
                return java.util.Set.of();
            }
        });
    }

    /**
     * Whether a task the restart took down needs its tokens from a vault that is locked right now.
     * <p>
     * Asked only for such a task, and only of the keyring - never by opening the vault - so a listing stays
     * cheap: the answer an interface needs to offer unlocking before anybody presses Start.
     *
     * @param container The task's container.
     * @return true when starting it would be refused until the vault is unlocked
     */
    private boolean needsLockedVault(String container) {
        final boolean needsTokens = TaskHelpers.readFrom(context.paths().tasks().taskRecord(container)).helpers().stream()
                .anyMatch(helper -> "gate".equals(helper.name()) || "vault".equals(helper.name()));
        return needsTokens && context.vault().exists() && context.opener().isEmpty();
    }

    private Task describe(ContainerSummary summary,
            Map<String, java.util.Set<String>> waitingCache) {
        final Sidecar sidecar = sidecarOf(summary.name());
        final Path state = context.paths().tasks().containerState(summary.name());
        // The saved copy when a restart emptied the runtime directory: which agent and mode are still true of it.
        final boolean restartTookIt = !summary.running() && !Files.isDirectory(state)
                && new TaskState(context).saved(summary.name());
        final org.fuin.sokar.wire.TaskProfile profile = org.fuin.sokar.wire.TaskProfile.readFrom(
                restartTookIt ? context.paths().tasks().taskRecord(summary.name()) : state);
        final String waitingFor = summary.running()
                ? org.fuin.sokar.wire.Waiting.about(state) : null;
        // Only by the deriving listing, and of a stopped task too while its state is here: an agent whose container
        // stopped with its run said only 'dead'.
        final AgentEnding.Ended ended = ending == null || restartTookIt ? null
                : ending.about(summary.name(), summary.running(), profile == null ? null : profile.agent(), state);
        return new Task(summary.name(),
                // The container's own label first: it survives a reboot, and the sidecar does not
                // - it lives in the runtime directory, which the system destroys when the user's
                // last session ends. Reported as every surviving task listing "-" for both after
                // the machine came back. The sidecar is the fallback, for containers a Sokar
                // without the labels created.
                summary.project() != null ? summary.project()
                        : sidecar == null ? null : sidecar.project(),
                summary.securityClass() != null ? summary.securityClass()
                        : sidecar == null ? null : sidecar.securityClass(),
                summary.state(), summary.running(), helpersOf(summary.name()),
                profile == null ? null : profile.agent(),
                // The enum's own name, like every other enum on the wire. The lower-case form
                // is the on-disk spelling and stops at the file: a client reading the contract
                // sees SHELL, AGENT, UNATTENDED and must get those.
                profile == null ? null : profile.mode().name(),
                profile == null ? null : profile.prompt(),
                profile == null ? null : profile.branch(),
                summary.since(),
                ended != null && summary.running() ? Activity.ENDED : activityOf(summary, state, waitingFor),
                waitingFor,
                profile == null ? null : profile.clearance(),
                profile == null ? null : profile.label(),
                // Only what waits at the gate. An online task's push is passed on as it arrives and is never
                // reviewed, so its gate lists nothing waiting.
                profile == null || profile.branch() == null
                        || !profile.branch().startsWith(org.fuin.sokar.gate.GitGate.INCOMING)
                        ? 0
                        : waitingIn(waitingCache, sidecar == null ? null : sidecar.project())
                                .contains(profile.branch().substring(
                                        org.fuin.sokar.gate.GitGate.INCOMING.length())) ? 1 : 0,
                // The same test resume makes, and a directory check rather than a runtime call:
                // a stopped task with no state directory was started before this machine
                // restarted, and Start would refuse it. Said here so nobody learns it by pressing.
                restartTookIt ? (needsLockedVault(summary.name()) ? "NEEDS_VAULT" : "RESUME")
                        : !summary.running() && !Files.isDirectory(state) ? "PREDATES_RESTART"
                        : summary.running() ? "RUNNING" : "RESUME",
                restartTookIt ? RESTARTED
                        : !summary.running() && !Files.isDirectory(state)
                        ? "started before this machine restarted; copy the workspace out with"
                                + " 'podman cp " + summary.name() + ":/workspace ./recovered',"
                                + " then 'sokar task remove " + summary.name() + " --force'"
                        : "",
                // From the container's label, like the project and the class above, and for the
                // same reason: it survives a reboot. There is no sidecar fallback because the
                // sidecar predates repositories - a task without the label worked on the only
                // repository its project had.
                summary.repository(),
                // From the container's label, like the three above. The project moves on; this
                // must not, or a question about a task is answered from a file that has changed.
                summary.commit(),
                agentWaiting == null ? AgentWaiting.Derived.NOTHING
                        : agentWaiting.about(summary.name(), summary.running(), profile == null ? null : profile.agent(),
                                profile == null ? null : profile.mode().name(), state),
                credentialsGiven(restartTookIt ? context.paths().tasks().taskRecord(summary.name()) : state),
                grantsGiven(restartTookIt ? context.paths().tasks().taskRecord(summary.name()) : state),
                profile == null ? null : profile.provider(), ended, HandIns.of(context).summary(summary.name()),
                buildsOf(summary.name(), summary.project() != null ? summary.project()
                        : sidecar == null ? null : sidecar.project(), summary.securityClass() != null
                        ? summary.securityClass() : sidecar == null ? null : sidecar.securityClass(), summary.running()));
    }

    /**
     * Returns what a person sees of a task's builds, with why nothing follows them where something should: an online
     * task whose project names a forge, and no helper that follows them - its launch stopped before it, or it ended.
     */
    private TaskBuilds.View buildsOf(final String container, final @Nullable String project,
            final @Nullable String securityClass, final boolean running) {
        final TaskBuilds.View view = new TaskBuilds(context.paths().tasks()).view(container);
        // Only for online tasks: the project file is read for them alone, and only they can follow a build.
        if (!"online".equalsIgnoreCase(String.valueOf(securityClass)) || !view.problem().isEmpty()) {
            return view;
        }
        if (view.reader().isEmpty()) {
            final org.fuin.sokar.core.project.Builds named = buildsNamedBy(project);
            return named == null ? view : new TaskBuilds.View(named.forge(), "the helper that follows its builds has"
                    + " not started: the task's launch has not reached it, or stopped before it; start the task again",
                    view.builds());
        }
        final java.nio.file.Path pid = context.paths().tasks().containerState(container)
                .resolve(TaskBuilds.HELPER + ".pid");
        if (running && org.fuin.sokar.wire.HelperPid.verified(pid).isEmpty()) {
            return new TaskBuilds.View(view.reader(), "the helper that follows its builds is not running; stop the"
                    + " task and start it again to bring it back", view.builds());
        }
        return view;
    }

    /** What a project's file in force names under builds; {@code null} when nothing, or it cannot be read. */
    private org.fuin.sokar.core.project.@Nullable Builds buildsNamedBy(final @Nullable String project) {
        if (project == null) {
            return null;
        }
        try {
            final java.nio.file.Path file = ProjectSource.resolve(context, project).file();
            return file == null ? null : org.fuin.sokar.core.project.ProjectReader.read(file).builds();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** What a task was given beyond its agent's own credential, written when it is launched. */
    static final String CREDENTIALS_FILE = "credentials.json";

    /** Whom a task acts as: for each credential a person granted, who and when, written when it is launched. */
    static final String GRANTS_FILE = "grants.json";

    private static Map<String, Map<String, String>> grantsGiven(Path directory) {
        final Path file = directory.resolve(GRANTS_FILE);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (org.fuin.sokar.wire.Json.parse(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8))
                    instanceof Map<?, ?> named) {
                final Map<String, Map<String, String>> given = new java.util.LinkedHashMap<>();
                named.forEach((name, grant) -> {
                    if (grant instanceof Map<?, ?> fields) {
                        given.put(String.valueOf(name), Map.of("grantedBy", String.valueOf(fields.get("grantedBy")),
                                "grantedAt", String.valueOf(fields.get("grantedAt"))));
                    }
                });
                return given;
            }
        } catch (java.io.IOException | RuntimeException ex) {
            // A file that cannot be read says nothing; the task is still listed.
        }
        return Map.of();
    }

    private static Map<String, String> credentialsGiven(Path directory) {
        final Path file = directory.resolve(CREDENTIALS_FILE);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (org.fuin.sokar.wire.Json.parse(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8))
                    instanceof Map<?, ?> named) {
                final Map<String, String> given = new java.util.LinkedHashMap<>();
                named.forEach((name, destination) -> given.put(String.valueOf(name), String.valueOf(destination)));
                return given;
            }
        } catch (java.io.IOException | RuntimeException ex) {
            // A file that cannot be read says nothing; the task is still listed.
        }
        return Map.of();
    }

    /**
     * Works out what the task's work is doing.
     * <p>
     * In this order on purpose. A container that is down is dead whatever else is lying about in
     * its directory; a task that says it is waiting is waiting, because the thing that asked said
     * so; and only then does anything look at output, which is the one signal that needs a clock -
     * so it decides between working and idle and never between waiting and anything.
     *
     * @param summary What the runtime says.
     * @param state The task's state directory.
     * @param waitingFor What it is waiting to be told, or {@code null}.
     * @return What the work is doing.
     */
    private static Activity activityOf(ContainerSummary summary, Path state,
            @Nullable String waitingFor) {
        if (!summary.running()) {
            return Activity.DEAD;
        }
        if (waitingFor != null) {
            return Activity.WAITING;
        }
        final Path log = state.resolve("task.log");
        if (!Files.isRegularFile(log)) {
            // Nothing here can see what it is doing: an attached session writes to a terminal.
            return Activity.UNKNOWN;
        }
        try {
            return Files.getLastModifiedTime(log).toInstant()
                    .isAfter(java.time.Instant.now().minus(QUIET))
                    ? Activity.WORKING : Activity.IDLE;
        } catch (java.io.IOException ex) {
            return Activity.UNKNOWN;
        }
    }

    /**
     * Reads what Sokar recorded for a container, or {@code null} when nothing is left.
     */
    private @Nullable Sidecar sidecarOf(String container) {
        try {
            final Path file = context.paths().tasks().containerState(container).resolve("sidecar.json");
            return Files.isRegularFile(file) ? Sidecar.readFrom(file) : null;
        } catch (java.io.IOException | RuntimeException ex) {
            // A task whose sidecar cannot be read is still worth listing, just with less detail.
            return null;
        }
    }

    /**
     * One log a task has written.
     *
     * @param name File name, which is what {@code Tail} takes.
     * @param bytes How large it is now.
     * @param at When it was last written, ISO-8601.
     */
    public record Log(String name, long bytes, String at, @Nullable String what) {

        /**
         * Returns this log as plain values, for a caller that has to put it on a wire.
         *
         * @return The log.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("bytes", bytes);
            map.put("at", at);
            // Left out rather than sent empty. A client draws absent as nothing; an empty string
            // under a name reads as a description that failed instead of one nobody gave.
            if (what != null && !what.isBlank()) {
                map.put("what", what);
            }
            return map;
        }
    }

    /**
     * Returns the logs one task has, newest content first.
     * <p>
     * <strong>Listed rather than guessed.</strong> Which files a task has depends on what it
     * started - a task with no gate has no {@code gate.log}, one run with {@code --clearance off}
     * has no {@code clearance.log} - so a client that knew the names would be showing an empty
     * viewer for a file that was never going to exist, and would never show one added by a later
     * release. Same rule as the prompt key: derive nothing at the far end that this end knows.
     *
     * @param container Container name.
     * @return The logs, alphabetical, empty when the task has no state directory left.
     */
    /**
     * Tells whether a file in a task's state directory is one somebody may read.
     * <p>
     * <strong>An allow-list, and it stays one.</strong> That directory also holds
     * {@code vault.token} - the live phantom token for the task - beside the sockets, the pid
     * files, the ruleset and the sidecar. "Everything that is not a secret" is a rule that has to
     * be right forever, including about files a later release adds; "these names" is a rule that
     * fails closed when somebody adds one.
     * <p>
     * <strong>Two files are logs without saying so in their name.</strong> {@code events.jsonl} is
     * what the firewall blocked, which is exactly what somebody debugging a task that started and
     * did nothing needs, and {@code reader.err} is where the reader hook's own failures go. The
     * suffix rule quietly hid both - reported by an operator who noticed the list was short.
     * <p>
     * Used by the daemon's {@code Tail} as well, so that what a listing offers is what a read
     * accepts. They were separate rules, which is a listing that names a file the reader refuses.
     *
     * @param name File name, not a path.
     * @return {@code true} if it may be listed and read.
     */
    public static boolean isLog(String name) {
        return name.endsWith(".log")
                || name.equals(org.fuin.sokar.wire.ReaderEvents.FILE)
                || name.equals("reader.err");
    }

    public List<Log> logs(String container) {
        final Path state = context.paths().tasks().containerState(container);
        if (!org.fuin.sokar.runtime.ContainerName.isTask(container)
                || !Files.isDirectory(state)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(state)) {
            return files.filter(file -> isLog(file.getFileName().toString()))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(TaskInventory::describe)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (java.io.IOException ex) {
            // A directory that vanished while being read is a task somebody removed, which is not
            // this method's problem to report.
            return List.of();
        }
    }

    /**
     * Returns what a log holds, for the ones whose name does not say.
     * <p>
     * <strong>Two files, and both of them are the ones somebody needs.</strong>
     * {@code events.jsonl} is what the firewall stopped - the file to read when a task starts and
     * then does nothing - and nothing in the name says so. {@code reader.err} is the process that
     * writes it complaining, which is why its sentence carries the consequence rather than the
     * description: empty is the normal case, so what matters is what a non-empty one means.
     * <p>
     * <strong>Here rather than in a client.</strong> A table at the other end would drift the day
     * a file is added: the list grows, the new one has no sentence, and it looks like the ordinary
     * case. Only this side knows which files exist and what wrote them.
     * <p>
     * <strong>Absent for everything else, deliberately.</strong> {@code gate.log} is the gate's
     * log; a sentence saying so is noise, and noise beside two lines that matter is what stops
     * them being read.
     *
     * @param name File name.
     * @return One line, or {@code null} when the name speaks for itself.
     */
    private static @Nullable String what(String name) {
        return switch (name) {
            case "events.jsonl" -> "connections the firewall stopped - read this when a task"
                    + " starts and then does nothing";
            case "reader.err" -> "the process that records those connections, complaining."
                    + " Anything in it means the record of blocks may be incomplete";
            default -> null;
        };
    }

    @Nullable
    private static Log describe(Path file) {
        try {
            final String name = file.getFileName().toString();
            return new Log(name, Files.size(file),
                    Files.getLastModifiedTime(file).toInstant().toString(), what(name));
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    /**
     * Counts the helper processes a task's state directory records as alive.
     */
    private long helpersOf(String container) {
        final Path state = context.paths().tasks().containerState(container);
        if (!Files.isDirectory(state)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(state)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".pid"))
                    .filter(TaskLifecycle::alive)
                    .count();
        } catch (java.io.IOException ex) {
            return 0;
        }
    }

    /**
     * Says why a container found by this task's name is another project's, or nothing when it is this one's.
     * <p>
     * A name is not enough: project and task names both take hyphens, so {@code web} with task {@code app-x} and
     * {@code web-app} with task {@code x} are one container name, and a start brought back the other project's task
     * with its workspace and its egress. The project label says whose it is; a container from before it is taken by
     * its name, as it always was.
     *
     * @param found The container of that name.
     * @param project The project starting the task.
     * @return The refusal, or {@code null}.
     */
    public static @Nullable String ofAnotherProject(org.fuin.sokar.runtime.ContainerSummary found, String project) {
        final String owner = found.project();
        if (owner == null || owner.isBlank() || owner.equals(project)) {
            return null;
        }
        return found.name() + " is a task of project '" + owner + "', not of '" + project
                + "': the two names meet. Give this task another name";
    }
}
