package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What the builds of a task's pushes did, as Sokar told the task: the record that outlives the task, and the view a
 * person sees while it runs.
 * <p>
 * The record holds what was delivered - commit, verdict, job, the log's name, size and hash - and never a line of a
 * log: the log is kept only in the task it was handed to, and goes with it.
 */
public final class TaskBuilds {

    private final TaskPaths paths;

    /**
     * A failing job's log as it was handed into the task.
     *
     * @param name Its name in {@code /sokar/files}.
     * @param bytes Its size.
     * @param sha256 Its content's hash, hex.
     */
    public record Log(String name, long bytes, String sha256) {
    }

    /**
     * One job of a build, and its log as it was handed into the task.
     *
     * @param name As the forge spells it, workflow and job.
     * @param result What became of it, as the forge says it.
     * @param log Its log, or {@code null} when none was delivered.
     */
    public record Job(String name, String result, @Nullable Log log) {

        /**
         * Returns it as the daemon sends it: the log as its name.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("result", result);
            map.put("log", log == null ? "" : log.name());
            return map;
        }
    }

    /**
     * One verdict for one commit, as it was handed into the task.
     *
     * @param commit The full sha the task pushed.
     * @param verdict queued, running, success, failure, cancelled or unknown.
     * @param jobs The jobs whose log reached the task, in the forge's order.
     * @param since UTC, when the verdict last changed.
     * @param detail Why it is unknown, or what more the forge said; "" otherwise.
     * @param run The container id of the task it went to.
     */
    public record Seen(String commit, String verdict, List<Job> jobs, String since, String detail, String run) {

        /**
         * Returns it as the daemon sends it, the agreed contract.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("commit", commit);
            map.put("verdict", verdict);
            map.put("jobs", jobs.stream().map(Job::asMap).toList());
            map.put("since", since);
            map.put("detail", detail);
            return map;
        }

        /**
         * Returns whether the verdict will not change any more.
         *
         * @return {@code true} for success, failure and cancelled.
         */
        public boolean finished() {
            return List.of("success", "failure", "cancelled").contains(verdict);
        }
    }

    /**
     * What a person sees of a task's builds.
     *
     * @param reader The forge whose builds of its pushes are followed; "" when none is named.
     * @param problem Why the named reader does not follow them; "" while it does.
     * @param builds Each push's newest verdict, the newest push first.
     */
    public record View(String reader, String problem, List<Seen> builds) {

        /**
         * Returns the view of a task whose builds nobody follows.
         *
         * @return It.
         */
        public static View none() {
            return new View("", "", List.of());
        }
    }

    /** The helper that follows a task's builds: its pid file, its log and its entry in the resume record. */
    public static final String HELPER = "builds";

    /** The file in a task's state that says which reader follows its builds, and what keeps it from doing so. */
    static final String READER_FILE = "builds.json";

    /**
     * Constructor.
     *
     * @param paths Where tasks keep their state.
     */
    public TaskBuilds(final TaskPaths paths) {
        this.paths = paths;
    }

    /**
     * Writes a verdict down.
     *
     * @param container The task.
     * @param seen What was handed in.
     * @throws IOException When it cannot be written.
     */
    public void write(final String container, final Seen seen) throws IOException {
        final Path record = recordOf(container);
        Files.createDirectories(record.getParent(), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        if (!Files.exists(record)) {
            Files.createFile(record, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        final Map<String, Object> line = new LinkedHashMap<>(seen.asMap());
        // The record keeps each log's size and hash beside its name: what was delivered, never a line of it.
        line.put("jobs", seen.jobs().stream().map(job -> {
            final Map<String, Object> each = new LinkedHashMap<>(job.asMap());
            if (job.log() != null) {
                each.put("log", Map.of("name", job.log().name(), "bytes", job.log().bytes(),
                        "sha256", job.log().sha256()));
            }
            return each;
        }).toList());
        line.put("run", seen.run());
        Files.writeString(record, Json.write(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /**
     * Returns every verdict written for a task name, oldest first: of every run that had it.
     *
     * @param container The task's name.
     * @return The record, empty when nothing was written.
     * @throws IOException When it cannot be read.
     */
    public List<Seen> record(final String container) throws IOException {
        final Path file = recordOf(container);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final List<Seen> seen = new ArrayList<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank() && Json.parse(line) instanceof Map<?, ?> map) {
                final List<Job> jobs = new ArrayList<>();
                if (map.get("jobs") instanceof List<?> listed) {
                    for (final Object entry : listed) {
                        if (entry instanceof Map<?, ?> job) {
                            final Log log = job.get("log") instanceof Map<?, ?> each ? new Log(text(each.get("name")),
                                    number(each.get("bytes")), text(each.get("sha256"))) : null;
                            jobs.add(new Job(text(job.get("name")), text(job.get("result")), log));
                        }
                    }
                }
                seen.add(new Seen(text(map.get("commit")), text(map.get("verdict")), jobs, text(map.get("since")),
                        text(map.get("detail")), text(map.get("run"))));
            }
        }
        return seen;
    }

    /**
     * Returns the builds of a task's current run, each commit's newest verdict, the newest commit first.
     *
     * @param container The task.
     * @return The builds; empty before the first push and where nothing can be read.
     */
    public List<Seen> current(final String container) {
        final String run = HandIns.runOf(paths, container);
        final Map<String, Seen> newest = new LinkedHashMap<>();
        try {
            for (final Seen seen : record(container)) {
                if (seen.run().equals(run)) {
                    // A commit keeps the place it was first seen at, so the order is the order of the pushes.
                    newest.put(seen.commit(), seen);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // An unreadable record says no build is known rather than failing the listing it is a part of.
            return List.of();
        }
        final List<Seen> builds = new ArrayList<>(newest.values());
        java.util.Collections.reverse(builds);
        return List.copyOf(builds);
    }

    /**
     * Writes down which reader follows a task's builds, and why it does not when it does not.
     *
     * @param container The task.
     * @param reader The forge, as the project names it.
     * @param problem Why it does not follow them; "" when it does.
     * @throws IOException When it cannot be written.
     */
    public void reader(final String container, final String reader, final String problem) throws IOException {
        final Path state = paths.containerState(container);
        Files.createDirectories(state);
        final Map<String, Object> said = new LinkedHashMap<>();
        said.put("reader", reader);
        said.put("problem", problem);
        Files.writeString(state.resolve(READER_FILE), Json.write(said) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Returns what a person sees of a task's builds.
     *
     * @param container The task.
     * @return The view; {@link View#none()} when nothing follows them.
     */
    public View view(final String container) {
        final Path file = paths.containerState(container).resolve(READER_FILE);
        try {
            if (Files.isRegularFile(file)
                    && Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> said) {
                return new View(text(said.get("reader")), text(said.get("problem")), current(container));
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable, it says nothing follows them rather than failing the listing it is a part of.
        }
        return View.none();
    }

    /** The record, in the state directory like the hand-ins', so it outlives the task. */
    private Path recordOf(final String container) {
        return paths.paths().xdg().state().resolve("builds").resolve(container + ".jsonl");
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(final @Nullable Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
