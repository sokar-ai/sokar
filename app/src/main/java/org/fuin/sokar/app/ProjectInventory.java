package org.fuin.sokar.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.gate.GateMode;
import org.fuin.sokar.gate.GitGate;
import org.jspecify.annotations.Nullable;

/**
 * What projects this machine knows about, from three sources that each know part of it.
 * <p>
 * The CLI renders this and the daemon serializes it, so "what projects are there" is answered
 * once. Nothing here is a store of its own: a project is not something Sokar creates, it is
 * somebody's directory with a {@code project.yml} in it, and this reports what has been seen of
 * one rather than pretending to own it.
 * <ul>
 * <li>The <strong>mirrors directory</strong> holds one repository per project that has ever used
 *     the gate. It outlives every task and is the only durable list of names there is.</li>
 * <li>The <strong>tasks</strong> that exist say which project each belongs to and how it is
 *     classified, which is how a project with no mirror yet still appears.</li>
 * <li>The <strong>registry</strong> says where each project's file is, recorded when a task was
 *     started with it - the one thing a remote client cannot work out for itself and needs for
 *     every gate call.</li>
 * </ul>
 */
public final class ProjectInventory {

    /** Suffix of a mirror directory, which is a bare repository. */
    private static final String MIRROR_SUFFIX = ".git";

    /**
     * One project, as much as this machine can say about it.
     *
     * @param name Project name, as its file declares it.
     * @param securityClass Its class, or {@code null} when no task recorded one.
     * @param file Absolute path of its {@code project.yml}, or {@code null} when unknown or when
     *        the recorded one is no longer there.
     * @param mirror The gate's mirror for it, or {@code null} when it has never used the gate.
     * @param pending How many pushes are waiting for review in that mirror.
     * @param tasks How many tasks of this project exist right now, running or stopped.
     * @param running How many of those are up. A project with none is not idle by mistake - a
     *        stopped task keeps its workspace and can be resumed - so both numbers are reported
     *        rather than one being inferred from the other.
     */
    public record Summary(String name, @Nullable String securityClass, @Nullable String file,
            @Nullable String mirror, int pending, int tasks, int running) {

        /**
         * Returns this project as plain values, for a caller that has to put it on a wire.
         *
         * @return The project, with {@code null} replaced by an empty string so the map is
         *         representable in every encoding a client might use.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("securityClass", securityClass == null ? "" : securityClass);
            map.put("file", file == null ? "" : file);
            map.put("mirror", mirror == null ? "" : mirror);
            map.put("pending", pending);
            map.put("tasks", tasks);
            map.put("running", running);
            return map;
        }
    }

    private final SokarContext context;

    /**
     * Constructor with the context to read from.
     *
     * @param context Where the paths and the runtime come from.
     */
    public ProjectInventory(SokarContext context) {
        this.context = context;
    }

    /**
     * Returns every project this machine knows about, alphabetically.
     * <p>
     * <strong>Every project, not only the busy ones.</strong> A project with nothing running is
     * the ordinary case - between tasks, or after a run was stopped and can still be resumed - and
     * an interface that only listed active ones would show an empty screen on a machine with a
     * dozen projects on it. Which are active is a number in each row rather than a filter here.
     *
     * @return The projects, empty when none has ever run a task or used the gate.
     */
    public List<Summary> projects() {

        final Map<String, String> files = new ProjectRegistry(context.paths().projectRegistry())
                .all();
        final Map<String, Summary> found = new LinkedHashMap<>();

        for (final String name : mirroredNames()) {
            found.put(name, new Summary(name, null, fileOf(files, name),
                    mirrorOf(name).toString(), pendingIn(name), 0, 0));
        }
        for (final TaskInventory.Task task : new TaskInventory(context).tasks()) {
            if (task.project() == null) {
                continue;
            }
            final Summary known = found.get(task.project());
            // A class recorded by a task wins over nothing, and the task count grows per task.
            found.put(task.project(), new Summary(task.project(),
                    task.securityClass() == null && known != null ? known.securityClass()
                            : task.securityClass(),
                    fileOf(files, task.project()),
                    known == null ? null : known.mirror(),
                    known == null ? 0 : known.pending(),
                    (known == null ? 0 : known.tasks()) + 1,
                    (known == null ? 0 : known.running()) + (task.running() ? 1 : 0)));
        }
        // A project whose file was recorded but that has neither a mirror nor a task: it ran once
        // and everything was removed. Still worth showing - the file is what an interface acts on.
        files.keySet().stream().filter(name -> !found.containsKey(name)).forEach(name ->
                found.put(name, new Summary(name, null, fileOf(files, name), null, 0, 0, 0)));

        return found.values().stream()
                .sorted(java.util.Comparator.comparing(Summary::name))
                .toList();
    }

    /**
     * Returns the path of a project's file, when one was recorded and is still there.
     * <p>
     * A file that has moved is reported as absent rather than as a path nothing can read: an
     * interface that called a gate method with it would be refused for a reason that looks like a
     * bug in the daemon.
     */
    @Nullable
    private static String fileOf(Map<String, String> files, String name) {
        final String path = files.get(name);
        return path != null && Files.isRegularFile(Path.of(path)) ? path : null;
    }

    private Path mirrorOf(String name) {
        return context.paths().xdg().data().resolve("mirrors").resolve(name + MIRROR_SUFFIX);
    }

    private List<String> mirroredNames() {
        final Path mirrors = context.paths().xdg().data().resolve("mirrors");
        if (!Files.isDirectory(mirrors)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(mirrors)) {
            final List<String> names = new ArrayList<>();
            entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(MIRROR_SUFFIX))
                    .forEach(name ->
                            names.add(name.substring(0, name.length() - MIRROR_SUFFIX.length())));
            names.sort(String::compareTo);
            return List.copyOf(names);
        } catch (java.io.IOException ex) {
            return List.of();
        }
    }

    /**
     * Counts what is waiting for review in a project's mirror.
     * <p>
     * Asked of git rather than counted as files under {@code refs/}: a mirror that has been packed
     * keeps its refs in one file, and a loose-file count would report a queue as empty the first
     * time git tidied up. The gate is built straight from the mirror path, without a project file,
     * because this is exactly the case where there may not be one - and the mode does not enter
     * into listing what has arrived.
     */
    private int pendingIn(String name) {
        final Path mirror = mirrorOf(name);
        if (!Files.isDirectory(mirror)) {
            return 0;
        }
        try {
            return new GitGate(context.runner(), mirror, GateMode.GATEKEEPING, null)
                    .pending().size();
        } catch (RuntimeException ex) {
            // A directory that is not a repository, or a git that would not run. Neither is worth
            // failing a listing for.
            return 0;
        }
    }
}
