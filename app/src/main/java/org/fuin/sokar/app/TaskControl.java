package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.runtime.ContainerName;
import org.fuin.sokar.runtime.ContainerSummary;
import org.jspecify.annotations.Nullable;

/**
 * Stopping and resuming a task, decided once for every caller.
 * <p>
 * The CLI renders what these return and the daemon serializes it, the way both already share
 * {@link TaskInventory}. The CLI and the interface have to reach identical behavior through the
 * same calls, and these two operations are where a second implementation would hurt most: they
 * refuse things. A refusal that exists in one caller and not the other is a task
 * removed with work in it.
 * <p>
 * Nothing here prints. What happened is returned, including the parts that read as failures, so
 * that a caller with no terminal can act on them.
 */
public final class TaskControl {

    /** What became of the request. */
    public enum Outcome {

        /** The name is not one Sokar created, so this refused to touch it. */
        NOT_A_TASK,

        /** There is no such task; stopping something already gone is not an error. */
        NOTHING_TO_STOP,

        /** Stopped, and removed when that was asked for. */
        STOPPED,

        /** Removal refused: the workspace holds work that reached nothing else. */
        HOLDS_WORK,

        /** Removal refused: it is stopped and nothing recorded what it holds. */
        NOTHING_KNOWS,

        /** Rescue refused: the task is stopped, so nothing can push from it. */
        RESCUE_NEEDS_IT_RUNNING,

        /** Rescue attempted and the work did not arrive; nothing was removed. */
        RESCUE_FAILED,

        /** Removed: the container is gone and its helpers were already down. */
        REMOVED,

        /**
         * Removal refused: the task is running.
         * <p>
         * Stopping ends an agent's session and removing destroys its work. Somebody who asked for
         * the second did not ask for the first, and until these were two verbs they could not say
         * so - {@code stop --purge} did both whatever the task was doing.
         */
        STILL_RUNNING,

        /** Started again, with the workspace it had. */
        RESUMED,

        /** Already up, so there was nothing to resume. */
        ALREADY_RUNNING,

        /** No container of that name to resume. */
        NO_CONTAINER,

        /** Resumed, but the task recorded no helpers for this version to start. */
        NO_HELPERS_RECORDED,

        /** The container came up but not every helper did. */
        HELPERS_INCOMPLETE,

        /**
         * The task was started before this machine restarted, so it cannot be started again.
         * <p>
         * Its state directory is under {@code $XDG_RUNTIME_DIR}, which the system clears when the
         * user's last session ends - and the container bind-mounts the broker socket from inside
         * it. podman refuses to start a container whose mount source is gone, with a message
         * about {@code crun} and a path, which says nothing about what happened or what to do.
         * The workspace is still in the container and can be copied out.
         */
        PREDATES_RESTART
    }

    /**
     * What stopping a task did.
     *
     * @param outcome What became of the request.
     * @param work What the workspace holds that never reached the gate, or {@code null}.
     * @param rescuedRef Ref the work was pushed to, or {@code null} when none was.
     * @param removed Whether the container was removed as well as stopped.
     * @param helpers How many helpers were running when this started.
     * @param surviving Helpers that were still alive afterwards, as pid and command.
     * @param state The task's state directory, or {@code null} once it was removed.
     * @param detail Why a rescue could not happen, or {@code null}. Three cases with three
     *        different fixes: a task with no gate ref, one that pushes straight to its upstream,
     *        and a push that did not arrive.
     * @param discarded How many paths the container had that its image did not - what the agent
     *        installed inside it - counted only when it was removed, because that is when they
     *        stopped existing. Zero otherwise.
     */
    public record Stopped(Outcome outcome, @Nullable String work, @Nullable String rescuedRef,
            boolean removed, int helpers, List<String> surviving, @Nullable Path state,
            @Nullable String detail, long discarded) {

        /** @return Whether anything is left running that should not be. */
        public boolean clean() {
            return surviving.isEmpty();
        }
    }

    /**
     * What resuming a task did.
     *
     * @param outcome What became of the request.
     * @param started How many helpers are up.
     * @param recorded How many the task recorded.
     * @param imageDrift The image name when it has been rebuilt since, otherwise {@code null}.
     * @param state The task's state directory, or {@code null} when it has none.
     * @param problems What could not be started, empty when everything did.
     */
    public record Resumed(Outcome outcome, int started, int recorded,
            @Nullable String imageDrift, @Nullable Path state, List<String> problems) {
    }

    private final SokarContext context;

    /**
     * Constructor with the context to act through.
     *
     * @param context Where podman and the paths come from.
     */
    public TaskControl(SokarContext context) {
        this.context = context;
    }

    /**
     * Stops a task and the helpers beside it, keeping everything it holds.
     *
     * @param container Container name.
     * @return What happened.
     */
    public Stopped stop(String container) {
        return stop(container, false, false, false);
    }

    /**
     * Removes a task: its container, its state, and the workspace inside it.
     * <p>
     * Refuses a running task rather than stopping it as a side effect. Stopping ends an agent's
     * session and removing destroys its work; somebody who typed one of those did not ask for the
     * other, and the two used to be the same command with a flag.
     *
     * @param container Container name.
     * @param rescue Push what the workspace holds to a ref of its own first.
     * @param force Stop it if it runs, and discard work that never reached the gate.
     * @return What happened.
     */
    public Stopped remove(String container, boolean rescue, boolean force) {
        // Not when rescuing: pushing what the workspace holds needs the container up, so refusing
        // a running one here would make --rescue impossible to use. Found by the tests that cover
        // rescue, which is what they are for.
        if (ContainerName.isTask(container) && !force && !rescue) {
            final boolean running = context.podman().sokarTasks().stream()
                    .filter(task -> task.name().equals(container))
                    .anyMatch(ContainerSummary::running);
            if (running) {
                return new Stopped(Outcome.STILL_RUNNING, null, null, false, 0, List.of(), null,
                        null, 0);
            }
        }
        return stop(container, true, rescue, force);
    }

    /**
     * Stops a task, and removes it when asked.
     *
     * @param container Container name.
     * @param purge Remove the container as well as stopping it.
     * @param rescue Push what the workspace holds to a ref of its own first.
     * @param force Remove it even though work would be lost.
     * @return What happened.
     */
    private Stopped stop(String container, boolean purge, boolean rescue, boolean force) {

        if (!ContainerName.isTask(container)) {
            return new Stopped(Outcome.NOT_A_TASK, null, null, false, 0, List.of(), null, null, 0);
        }

        final Path state = context.paths().containerState(container);
        final java.util.Optional<ContainerSummary> summary = context.podman().sokarTasks().stream()
                .filter(task -> task.name().equals(container)).findFirst();
        final boolean known = summary.isPresent() || Files.isDirectory(state);
        final boolean running = summary.map(ContainerSummary::running).orElse(false);

        final UnhandedWork.Held held = running ? heldBy(container) : UnhandedWork.read(state);
        final String work = held.phrase();

        // "nobody looked" rather than "held nothing": only the first is a reason to refuse.
        if (purge && summary.isPresent() && !running && !held.readable() && !force) {
            return new Stopped(Outcome.NOTHING_KNOWS, null, null, false, 0, List.of(), state,
                    null, 0);
        }
        if (work != null && purge && !rescue && !force) {
            return new Stopped(Outcome.HOLDS_WORK, work, null, false, 0, List.of(), state, null,
                    0);
        }
        String rescued = null;
        if (work != null && rescue) {
            if (!running) {
                return new Stopped(Outcome.RESCUE_NEEDS_IT_RUNNING, work, null, false, 0,
                        List.of(), state, null, 0);
            }
            final Rescue attempt = rescue(container);
            if (attempt.ref() == null) {
                return new Stopped(Outcome.RESCUE_FAILED, work, null, false, 0, List.of(), state,
                        attempt.problem(), 0);
            }
            rescued = attempt.ref();
        }

        // Counted first: stopping the container fires the poststop hook, which reaps the helpers
        // and deletes their pid files, leaving nothing to count afterwards.
        final List<ProcessHandle> helpers = TaskLifecycle.running(state);

        // Counted before the container goes, because afterwards there is nothing left to ask.
        final long discarded = purge ? context.podman().addedPaths(container) : 0;

        if (purge) {
            context.podman().remove(container);
        } else {
            context.podman().stop(container);
            // Written on the way down, while the answer is still knowable. Whoever removes this
            // task later cannot ask the container itself.
            if (running) {
                UnhandedWork.note(state, held);
            }
        }
        TaskLifecycle.stopHelpers(state);

        if (!known) {
            return new Stopped(Outcome.NOTHING_TO_STOP, null, null, false, 0, List.of(), null,
                    null, 0);
        }

        // Waited for first: termination is asynchronous, so checking straight away reports live
        // processes that are already on their way out.
        for (final ProcessHandle helper : helpers) {
            try {
                helper.onExit().get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException
                    | java.util.concurrent.ExecutionException ex) {
                // Still running, which the caller reports.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        final List<String> surviving = helpers.stream().filter(ProcessHandle::isAlive)
                .map(alive -> alive.pid() + " " + alive.info().command().orElse("?")).toList();

        if (purge) {
            deleteTree(state);
        }
        return new Stopped(Outcome.STOPPED, work, rescued, purge, helpers.size(), surviving,
                purge || !Files.isDirectory(state) ? null : state, null, discarded);
    }

    /**
     * Describes what the agent has done and not handed back, or {@code null} when there is none.
     * <p>
     * Only answerable while the container runs, which is why it is asked before anything is
     * stopped.
     * <p>
     * Public because the end of an attached run asks it too, and for the same reason: a workspace
     * lives in the container's own writable layer, so removing the container destroys it. Asking
     * here and not there was the difference between {@code task stop --purge}, which refuses, and
     * walking out of a shell, which did not.
     *
     * @param container Container name.
     * @return What is held, or {@code null}.
     */
    public UnhandedWork.Held heldBy(String container) {
        final CommandResult result = context.podman().ask(container, Map.of(),
                List.of("sh", "-c", "cd " + TaskWorkspace.MOUNT + " 2>/dev/null || exit 0;"
                        + " printf '%s %s' \"$(git status --porcelain 2>/dev/null | wc -l)\""
                        + " \"$(git log --oneline --branches --not --remotes 2>/dev/null"
                        + " | wc -l)\""));
        return result.successful() ? UnhandedWork.census(result.trimmedOutput())
                : UnhandedWork.Held.unknown();
    }

    /**
     * Pushes what the agent never handed back, under a ref of its own.
     * <p>
     * Rescued work is not work an agent offered up, so it lands beside the reviewed ref rather
     * than in it. Only a gated task can be rescued: one that pushes straight to a real upstream
     * has no place to put unreviewed work.
     *
     * @param container Container name.
     * @return The ref it landed on, or {@code null} when it did not.
     */
    private Rescue rescue(String container) {

        final CommandResult reference = context.podman().ask(container, Map.of(),
                List.of("sh", "-c", "printf '%s' \"$SOKAR_TASK_REF\""));
        if (!reference.successful()) {
            return new Rescue(null, "the task has no gate ref, so there is nowhere to rescue it to");
        }
        final String taskRef = reference.trimmedOutput();
        if (!taskRef.startsWith("refs/sokar/incoming/")) {
            return new Rescue(null, "this task pushes straight to its upstream, so unreviewed work"
                    + " cannot be rescued without publishing it");
        }
        final String rescueRef = taskRef + "-rescued";
        final CommandResult pushed = context.podman().ask(container,
                Map.of("SOKAR_TASK_REF", rescueRef), TaskWorkspace.pushCommand());
        // The output is checked as well as the exit code: this answer decides whether a container
        // holding the only copy of something is removed, so "it did not fail" is not enough.
        if (!pushed.successful() || pushed.trimmedOutput().contains("nothing to push")) {
            return new Rescue(null, "could not push the work: " + (pushed.trimmedOutput().isEmpty()
                    ? pushed.standardError().strip() : pushed.trimmedOutput()));
        }
        return new Rescue(rescueRef, null);
    }

    /** An attempted rescue: the ref it landed on, or why it did not. */
    private record Rescue(@Nullable String ref, @Nullable String problem) {
    }

    private void deleteTree(Path directory) {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ex) {
                    // Best effort: what is left is a directory the runtime clears at logout.
                }
            });
        } catch (java.io.IOException ex) {
            // Same.
        }
    }


    /**
     * Starts a stopped task again, with the workspace it already has.
     * <p>
     * The container is started rather than rebuilt, so the agent's work, its branch and its
     * uncommitted changes are all still there. What has to be started again is everything that
     * lives on the host - the credential proxy, the git gate, the clearance watcher - none of
     * which can be reconstructed from the container, which is why each was written down when the
     * task first ran.
     *
     * @param container Container name.
     * @return What happened.
     */
    /** What labelling a task did. */
    public enum Labelled {

        /** The caption was written down. */
        LABELLED,

        /** The caption was removed, because it was blank or absent. */
        CLEARED,

        /** No such task, or its state directory is gone. */
        NOT_A_TASK,

        /** It exists but recorded nothing about itself - started by an older Sokar. */
        NOT_RECORDED,

        /** The profile could not be written. */
        FAILED
    }

    /**
     * Gives a task a changeable caption, beside the name that identifies it.
     * <p>
     * <strong>Nothing about the identity moves.</strong> The container keeps its name, the gate
     * keeps its ref, the workspace and the logs stay where they are. A rename would have to move
     * all four - including a ref that may have unreviewed pushes behind it - which is why what
     * was asked for is a caption and not a rename.
     * <p>
     * Written into the task's own profile, so it survives a resume and outlives the container, and
     * so it is exactly as durable as the mode and the prompt beside it.
     *
     * @param container Container name.
     * @param caption The caption, or {@code null} / blank to remove one.
     * @return What happened.
     */
    public Labelled label(String container, @Nullable String caption) {

        if (!ContainerName.isTask(container)) {
            return Labelled.NOT_A_TASK;
        }
        final Path state = context.paths().containerState(container);
        if (!Files.isDirectory(state)) {
            return Labelled.NOT_A_TASK;
        }
        final org.fuin.sokar.wire.TaskProfile profile =
                org.fuin.sokar.wire.TaskProfile.readFrom(state);
        if (profile == null) {
            // Nothing to change without inventing the rest of the profile, and a profile invented
            // here would claim a mode and a start time nobody recorded.
            return Labelled.NOT_RECORDED;
        }
        try {
            profile.withLabel(caption).writeTo(state);
        } catch (java.io.IOException ex) {
            return Labelled.FAILED;
        }
        return caption == null || caption.isBlank() ? Labelled.CLEARED : Labelled.LABELLED;
    }

    public Resumed resume(String container) {

        if (!ContainerName.isTask(container)) {
            return new Resumed(Outcome.NOT_A_TASK, 0, 0, null, null, List.of());
        }
        final Path state = context.paths().containerState(container);
        final TaskHelpers recorded = TaskHelpers.readFrom(state);
        final Path logs = Files.isDirectory(state) ? state : null;

        if (context.podman().idOf(container).isEmpty()) {
            return new Resumed(Outcome.NO_CONTAINER, 0, 0, null, null, List.of());
        }

        // A container that is already up has nothing to resume, and starting its helpers a second
        // time is not harmless: each one writes the same pid file, so the earlier process is no
        // longer named by anything and nothing reaps it. Measured - two resumes of a running task
        // left two 'shield watch' processes re-parented to init, which 'task stop' then reported
        // as "4 of 4 stopped" while they went on running. Symmetrical with stopping a task that
        // has already gone: doing it twice is not an error, it is simply already done.
        if (context.podman().sokarTasks().stream()
                .anyMatch(task -> task.name().equals(container) && task.running())) {
            return new Resumed(Outcome.ALREADY_RUNNING, 0, recorded.helpers().size(), null, logs,
                    List.of());
        }

        if (!Files.isDirectory(state)) {
            // Not "no helpers recorded": the whole directory is gone, which happens exactly once
            // - when the machine restarts - and no amount of starting helpers brings back the
            // socket the container is bound to. Answered before podman is asked, so the operator
            // gets a sentence about what happened rather than crun's about a path.
            return new Resumed(Outcome.PREDATES_RESTART, 0, 0, null, null, List.of());
        }

        if (recorded.helpers().isEmpty()) {
            context.podman().start(container);
            return new Resumed(Outcome.NO_HELPERS_RECORDED, 0, 0, null, logs, List.of());
        }

        final String drift = imageDrift(container);
        final List<String> problems = new java.util.ArrayList<>();

        // The proxy's socket is mounted into the container, and a mount is bound to the file that
        // existed when the container started. Starting the container first therefore binds it to
        // the socket of the previous run, which the resumed proxy then replaces: measured, the
        // container held a deleted inode and every request through it went nowhere, while the
        // same request from the host was answered.
        int started = start(recorded, TaskHelpers.BEFORE, 0, state, problems);
        awaitHelpers(recorded, state);

        context.podman().start(container);

        final long pid = context.podman().pidOf(container).orElse(0L);
        if (pid <= 0) {
            problems.add("the container did not come up; see " + state);
            return new Resumed(Outcome.HELPERS_INCOMPLETE, started, recorded.helpers().size(),
                    drift, logs, List.copyOf(problems));
        }

        started += start(recorded, TaskHelpers.AFTER, pid, state, problems);
        return new Resumed(started == recorded.helpers().size()
                ? Outcome.RESUMED : Outcome.HELPERS_INCOMPLETE,
                started, recorded.helpers().size(), drift, logs, List.copyOf(problems));
    }

    /**
     * Replaces the container pid the watcher was started with, which belongs to the previous run.
     *
     * @param command The recorded command.
     * @param pid The pid the container has now.
     * @return The command to start.
     */
    private static List<String> withPid(List<String> command, long pid) {
        final List<String> updated = new ArrayList<>(command);
        final int flag = updated.indexOf("--pid");
        if (flag >= 0 && flag + 1 < updated.size()) {
            updated.set(flag + 1, String.valueOf(pid));
        }
        return updated;
    }

    /**
     * Adds the flag that makes the proxy keep the token the container already holds.
     *
     * @param command The recorded command.
     * @return The command to start.
     */
    private static List<String> reusingToken(List<String> command) {
        if (command.contains("--reuse-token")) {
            return command;
        }
        final List<String> updated = new ArrayList<>(command);
        updated.add("--reuse-token");
        return updated;
    }

    /**
     * Starts the helpers of one phase.
     *
     * @param recorded What the task wrote down.
     * @param phase Which phase to start.
     * @param pid Container pid, for helpers that need it; ignored when zero.
     * @param state The task's state directory.
     * @param problems Collects what could not be started, for a caller with no terminal.
     * @return How many started.
     */
    private int start(TaskHelpers recorded, String phase, long pid, Path state,
            List<String> problems) {

        int started = 0;
        for (final TaskHelpers.Helper helper : recorded.helpers()) {
            if (!phase.equals(helper.phase())) {
                continue;
            }
            // One that is still alive is not started again: every helper of a given name writes
            // the same pid file, so a second one would leave the first named by nothing and
            // reapable by nothing. Counted as started, because it is running - which is what the
            // caller is about to report.
            if (TaskLifecycle.alive(state.resolve(helper.name() + ".pid"))) {
                started++;
                continue;
            }
            List<String> command = helper.command();
            if ("watcher".equals(helper.name()) && pid > 0) {
                command = withPid(command, pid);
            }
            if ("vault".equals(helper.name())) {
                command = reusingToken(command);
            }
            try {
                final ProcessBuilder builder = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect
                                .appendTo(state.resolve(helper.name() + ".log").toFile()));
                builder.environment().putAll(helper.environment());
                builder.start();
                started++;
            } catch (IOException ex) {
                problems.add("could not start the " + helper.name() + ": " + ex.getMessage());
            }
        }
        return started;
    }

    /**
     * Waits for the helpers that must be up before the container to write their pid files.
     * <p>
     * Starting the container while the proxy is still binding its socket is the same failure as
     * starting it too early: the mount catches whatever is at the path at that moment.
     *
     * @param recorded What the task wrote down.
     * @param state The task's state directory.
     */
    private void awaitHelpers(TaskHelpers recorded, Path state) {
        final List<Path> expected = recorded.helpers().stream()
                .filter(helper -> TaskHelpers.BEFORE.equals(helper.phase()))
                .map(helper -> state.resolve(helper.name() + ".pid"))
                .toList();
        for (int attempt = 0; attempt < 50; attempt++) {
            if (expected.stream().allMatch(Files::isRegularFile)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Returns whether the project's image has been rebuilt since this task was created.
     * <p>
     * The task deliberately keeps the image it has - everything installed in the container since
     * it started would be lost otherwise, which is the opposite of what resuming is for. The
     * operator is told rather than upgraded, because only they know whether the difference
     * matters.
     *
     * @param container Container name.
     * @return The image name when it has drifted, otherwise {@code null}.
     */
    public @Nullable String imageDrift(String container) {
        return context.podman().imageOf(container).flatMap(image ->
                context.podman().imageId(image[0])
                        .filter(now -> !now.equals(image[1]))
                        .map(now -> image[0])).orElse(null);
    }
}
