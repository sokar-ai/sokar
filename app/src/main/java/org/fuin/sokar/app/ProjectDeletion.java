package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.gate.GateMode;
import org.fuin.sokar.gate.GitGate;
import org.jspecify.annotations.Nullable;

/**
 * Removes what Sokar built for a project.
 * <p>
 * <strong>Not "deleting the project".</strong> The project file is the operator's, in their own
 * directory, usually beside their own repository, and so is the checkout and the real upstream.
 * None of them is touched. What goes is the gate mirror, the task image, the build directory, the
 * registry entry, the recorded upstream distance, and every task with its container, state and
 * logs. Afterwards the project file is still there and running a task in that directory builds all
 * of it again - which is the property that makes this safe to offer at all.
 * <p>
 * <strong>It refuses rather than decides.</strong> Unreviewed pushes exist only in the mirror -
 * nothing else has them, anywhere - and a running task is work being cut off mid-flight. Both stop
 * it, both are named, and {@code force} is how somebody says they meant it. That is the shape
 * {@link TaskControl#stop} already has, so saying it twice means the same thing here as there.
 */
public final class ProjectDeletion {

    /** What became of a deletion. */
    public enum Outcome {

        /** Everything Sokar built for it is gone. */
        DELETED,

        /** What would go, having removed nothing. */
        PREVIEWED,

        /** Refused: work reached the gate and nobody reviewed it. {@code unreviewed} names it. */
        HOLDS_WORK,

        /** Refused: tasks are up. {@code running} names them. */
        TASKS_RUNNING,

        /** Nothing here knows that project. */
        NO_SUCH_PROJECT,

        /** Something could not be removed. {@code detail} says what. */
        FAILED
    }

    /**
     * One thing a deletion removes.
     *
     * @param kind MIRROR, IMAGE, BUILD, REGISTRY, UPSTREAM_RECORD or TASK.
     * @param what The thing itself, as a person would recognise it.
     */
    public record Removal(String kind, String what) {

        /**
         * Returns this as plain values, for a caller that has to put it on a wire.
         *
         * @return The removal.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("what", what);
            return map;
        }
    }

    /**
     * What a deletion did, or would do.
     *
     * @param outcome What became of it.
     * @param removes What goes, or went.
     * @param keeps What is deliberately not touched, named so a confirmation can say it.
     * @param unreviewed Refs nobody reviewed.
     * @param running Tasks that are up.
     * @param detail Why it was refused or what went wrong, or {@code null}.
     */
    public record Result(Outcome outcome, List<Removal> removes, List<String> keeps,
            List<String> unreviewed, List<String> running, @Nullable String detail) {

        /**
         * Constructor with defensive copies.
         *
         * @param outcome What became of it.
         * @param removes What goes.
         * @param keeps What stays.
         * @param unreviewed Refs nobody reviewed.
         * @param running Tasks that are up.
         * @param detail Why, or {@code null}.
         */
        public Result {
            removes = List.copyOf(removes);
            keeps = List.copyOf(keeps);
            unreviewed = List.copyOf(unreviewed);
            running = List.copyOf(running);
        }

        /**
         * Returns this as plain values.
         *
         * @return The result.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("outcome", outcome.name());
            map.put("removes", removes.stream().map(Removal::asMap).toList());
            map.put("keeps", keeps);
            map.put("unreviewed", unreviewed);
            map.put("running", running);
            map.put("detail", detail == null ? "" : detail);
            return map;
        }
    }

    private final SokarContext context;

    /**
     * Constructor with the context to act through.
     *
     * @param context Where the paths, the runtime and the registry come from.
     */
    public ProjectDeletion(SokarContext context) {
        this.context = context;
    }

    /**
     * Removes what Sokar built for a project, or reports what that would be.
     *
     * @param project Project name, as {@code Projects} reports it.
     * @param dryRun Whether to report rather than remove.
     * @param force Whether to proceed despite unreviewed work or running tasks.
     * @return What happened, or would have.
     */
    public Result delete(String project, boolean dryRun, boolean force) {

        final ProjectInventory.Summary summary = new ProjectInventory(context).projects().stream()
                .filter(candidate -> candidate.name().equals(project))
                .findFirst().orElse(null);
        if (summary == null) {
            return new Result(Outcome.NO_SUCH_PROJECT, List.of(), List.of(), List.of(), List.of(),
                    "nothing here knows a project called " + project);
        }

        final List<TaskInventory.Task> tasks = new TaskInventory(context).tasks().stream()
                .filter(task -> project.equals(task.project())).toList();
        final List<String> running = tasks.stream().filter(TaskInventory.Task::running)
                .map(TaskInventory.Task::name).toList();
        final List<String> unreviewed = unreviewedIn(project);

        // Named so a confirmation can say what survives. The interface would otherwise have to
        // know which of a project's things are Sokar's, and being wrong about ownership in a
        // dialog whose job is to say truthfully what is destroyed is the worst place for it.
        final List<String> keeps = new ArrayList<>();
        if (summary.file() != null) {
            keeps.add(summary.file());
        }
        keeps.add("the checkout and the real upstream, wherever they are");

        final List<Removal> removes = plan(project, summary, tasks);

        if (!force && !unreviewed.isEmpty()) {
            return new Result(Outcome.HOLDS_WORK, removes, keeps, unreviewed, running,
                    unreviewed.size() + " push(es) reached the gate and nobody reviewed them."
                            + " Nothing else has them: review or reject them, or say force.");
        }
        if (!force && !running.isEmpty()) {
            return new Result(Outcome.TASKS_RUNNING, removes, keeps, unreviewed, running,
                    running.size() + " task(s) are still up. Stop them, or say force.");
        }
        if (dryRun) {
            return new Result(Outcome.PREVIEWED, removes, keeps, unreviewed, running, null);
        }

        try {
            for (final TaskInventory.Task task : tasks) {
                // Through the same removal 'task remove' uses, so a deletion writes down what
                // a task held exactly as a deliberate removal does. force carries through: a
                // deletion the operator confirmed is not asked again per task.
                //
                // And the answer is READ. It was discarded, and that quietly broke the promise at
                // the top of this class: a task refusing because it holds work the gate never saw
                // was ignored, and the mirror was deleted a moment later - the one place those
                // pushes exist. Nothing reported it, because nothing looked.
                final TaskControl.Stopped removal =
                        new TaskControl(context).remove(task.name(), false, force);
                if (!removal.removed()) {
                    return new Result(Outcome.HOLDS_WORK, removes, keeps,
                            List.of(task.name()), running,
                            "task " + task.name() + " was not removed: "
                                    + removal.outcome().name().toLowerCase(java.util.Locale.ROOT)
                                            .replace('_', ' ')
                                    + (removal.work() == null ? "" : " - " + removal.work())
                                    + ". Nothing else has been removed. Deal with it, or say"
                                    + " force.");
                }
            }
            context.podman().removeImage("sokar/" + project);
            deleteTree(mirrorOf(project));
            deleteTree(context.paths().buildContext(project));
            Files.deleteIfExists(context.paths().projectRegistry().resolve(project));
            Files.deleteIfExists(context.paths().upstreamRecords().resolve(project));
        } catch (IOException | RuntimeException ex) {
            return new Result(Outcome.FAILED, removes, keeps, unreviewed, running,
                    "could not finish removing " + project + ": " + ex.getMessage());
        }
        return new Result(Outcome.DELETED, removes, keeps, unreviewed, running, null);
    }

    private List<Removal> plan(String project, ProjectInventory.Summary summary,
            List<TaskInventory.Task> tasks) {
        final List<Removal> removes = new ArrayList<>();
        if (summary.mirror() != null) {
            removes.add(new Removal("MIRROR", summary.mirror()));
        }
        if (summary.prepared()) {
            removes.add(new Removal("IMAGE", "sokar/" + project));
        }
        if (Files.isDirectory(context.paths().buildContext(project))) {
            removes.add(new Removal("BUILD", context.paths().buildContext(project).toString()));
        }
        if (Files.isRegularFile(context.paths().projectRegistry().resolve(project))) {
            removes.add(new Removal("REGISTRY",
                    context.paths().projectRegistry().resolve(project).toString()));
        }
        if (Files.isRegularFile(context.paths().upstreamRecords().resolve(project))) {
            removes.add(new Removal("UPSTREAM_RECORD",
                    context.paths().upstreamRecords().resolve(project).toString()));
        }
        tasks.forEach(task -> removes.add(new Removal("TASK", task.name())));
        return removes;
    }

    private List<String> unreviewedIn(String project) {
        final Path mirror = mirrorOf(project);
        if (!Files.isDirectory(mirror)) {
            return List.of();
        }
        try {
            return List.copyOf(new GitGate(context.runner(), mirror, GateMode.GATEKEEPING, null)
                    .pending());
        } catch (RuntimeException ex) {
            // A mirror that cannot be asked is a mirror that may be holding work. Refusing to
            // answer is safer than answering "none": the whole point of this list is to stop a
            // deletion, and an empty one lets it through.
            throw new IllegalStateException("cannot read what is waiting in " + mirror, ex);
        }
    }

    private Path mirrorOf(String project) {
        return context.paths().xdg().data().resolve("mirrors").resolve(project + ".git");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
