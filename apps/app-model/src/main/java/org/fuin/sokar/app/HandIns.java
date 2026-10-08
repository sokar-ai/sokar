package org.fuin.sokar.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.fuin.sokar.runtime.Containerfile;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Files handed to a running task: taken in parts, placed whole in {@code /sokar/files}, and written down.
 * <p>
 * <strong>Inbound only.</strong> Nothing here takes a file out of a task: content leaving a task is what the gate is
 * for, and a copy out would put bytes the agent controls on the host.
 * <p>
 * <strong>Complete or not at all.</strong> A file arrives on the host in parts, under a name of its own in the
 * runtime directory, and goes into the container only once its size and its sha256 are what the caller said. There it
 * is written into {@code /sokar/.incoming}, which the agent cannot look into, and renamed into {@code /sokar/files} on
 * the same filesystem - so an agent that looks at any moment sees the whole file or none of it.
 * <p>
 * <strong>Root's, read-only to the agent.</strong> Placed as root, mode 0644, in a directory the agent cannot write:
 * the agent reads what it was given and cannot change or remove it; taking it back is a person's act.
 * <p>
 * <strong>Written down, never kept.</strong> Every hand-in, replacement and removal is a line in the state directory -
 * who, when, which run, the name, the size and the sha256 - and the record outlives the task, as the clearance journal
 * does. The content is not in it: a handed-in file is often the very thing that is deliberately not in git. And it
 * records what this did, not what reached the container: a {@code podman cp} by hand is not in it.
 */
public final class HandIns {

    /** Who handed a file in, when it was Sokar itself rather than a person. */
    public static final String SOKAR = "sokar";

    /** The file in a task's state that holds its hand-in limit, set when the task starts. */
    public static final String LIMIT_FILE = TaskRunner.HAND_IN_LIMIT_FILE;

    /** How long a hand-in nobody continues is kept before its parts are dropped. */
    static final Duration ABANDONED = Duration.ofMinutes(10);

    /** An event of the record. */
    public static final String GIVEN = "given";

    /** An event of the record: a file of that name was there and is replaced. */
    public static final String REPLACED = "replaced";

    /** An event of the record. */
    public static final String TAKEN_BACK = "taken back";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** One lock per task, so two calls for the same task never interleave their parts or their record. */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private final TaskPaths paths;

    private final org.fuin.sokar.runtime.Podman podman;

    private final Runner runner;

    private final Clock clock;

    /**
     * Runs a command, feeding it a file on standard input when one is given.
     */
    @FunctionalInterface
    public interface Runner {

        /**
         * Runs it.
         *
         * @param arguments The command.
         * @param stdin A file to feed it, or {@code null}.
         * @return Its exit code.
         * @throws IOException When it cannot be started.
         */
        int run(List<String> arguments, @Nullable Path stdin) throws IOException;
    }

    /**
     * One handed-in file, as the record holds it.
     *
     * @param name As it appears in {@code /sokar/files}.
     * @param bytes Its size.
     * @param sha256 Its content's hash, hex.
     * @param at When it was complete in the task, UTC.
     * @param by Who handed it in: an account, or {@link #SOKAR}.
     * @param run The container id of the task it went to.
     */
    public record HandedFile(String name, long bytes, String sha256, String at, String by, String run) {

        /**
         * Returns it as the daemon sends it.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("bytes", bytes);
            map.put("sha256", sha256);
            map.put("at", at);
            map.put("by", by);
            map.put("run", run);
            return map;
        }
    }

    /**
     * One line of the record.
     *
     * @param event {@link #GIVEN}, {@link #REPLACED} or {@link #TAKEN_BACK}.
     * @param file The file.
     */
    public record Entry(String event, HandedFile file) {

        /**
         * Returns it as the daemon sends it.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("event", event);
            map.put("file", file.asMap());
            return map;
        }
    }

    /**
     * What a task says about its hand-ins.
     *
     * @param run The container id, or "" when none is recorded.
     * @param limit The bytes one file may have.
     * @param files What is in {@code /sokar/files} now, by this run.
     */
    public record Summary(String run, long limit, List<HandedFile> files) {

        /**
         * Returns the summary of a task nothing is known about.
         *
         * @return No run, the default limit, no files.
         */
        public static Summary none() {
            return new Summary("", org.fuin.sokar.core.project.Limits.DEFAULT_HAND_IN, List.of());
        }
    }

    /**
     * How far a hand-in has come.
     *
     * @param received Bytes the daemon holds for it.
     * @param file The file, once the last part made it complete and it is in the task; {@code null} before.
     */
    public record Progress(long received, @Nullable HandedFile file) {
    }

    /**
     * A hand-in refused, as a named answer: the daemon sends it as the error of that name.
     */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** The error's name in the interface. */
        private final String error;

        /** Its parameters. */
        private final transient Map<String, Object> parameters;

        Refused(final String error, final Map<String, Object> parameters, final String message) {
            super(message);
            this.error = error;
            this.parameters = Map.copyOf(parameters);
        }

        /**
         * Returns the error's name.
         *
         * @return Such as {@code FileTooLarge}.
         */
        public String error() {
            return error;
        }

        /**
         * Returns its parameters.
         *
         * @return Name to value.
         */
        public Map<String, Object> parameters() {
            return parameters;
        }
    }

    /**
     * Constructor.
     *
     * @param paths Where a task's state and the record are.
     * @param podman The runtime, for the commands that place a file.
     * @param runner Runs those commands.
     * @param clock The time a record line carries.
     */
    public HandIns(final TaskPaths paths, final org.fuin.sokar.runtime.Podman podman, final Runner runner,
            final Clock clock) {
        this.paths = paths;
        this.podman = podman;
        this.runner = runner;
        this.clock = clock;
    }

    /**
     * Returns the ordinary one: commands run as processes, the time the system's.
     *
     * @param context The machine.
     * @return Hand-ins for it.
     */
    public static HandIns of(final SokarContext context) {
        return new HandIns(context.paths().tasks(), context.podman(), HandIns::process, Clock.systemUTC());
    }

    /**
     * Takes one part of a file for a running task.
     *
     * @param container The task.
     * @param name The file's name in {@code /sokar/files}.
     * @param bytes The whole file's size.
     * @param sha256 The whole file's hash, hex.
     * @param offset Where this part starts.
     * @param part The part.
     * @param by Who hands it in.
     * @return How far it has come, and the file once it is in the task.
     * @throws Refused When the name, the size, the order or the content is not what it has to be.
     * @throws IOException When the part cannot be kept or the file cannot be placed.
     */
    public Progress part(final String container, final String name, final long bytes, final String sha256,
            final long offset, final byte[] part, final String by) throws IOException {
        checkName(name);
        if (!SHA256.matcher(sha256).matches()) {
            throw new Refused("FileNameRefused", Map.of("name", name, "reason",
                    "sha256 must be 64 lower-case hex digits"), "sha256 must be 64 lower-case hex digits");
        }
        final long limit = limit(container);
        if (bytes < 0 || bytes > limit) {
            throw new Refused("FileTooLarge", Map.of("bytes", bytes, "limit", limit),
                    name + " has " + bytes + " bytes; this task takes at most " + limit);
        }
        synchronized (LOCKS.computeIfAbsent(container, any -> new Object())) {
            final Path staging = staging(container);
            sweep(staging);
            final Path data = staging.resolve(name + ".part");
            final Path meta = staging.resolve(name + ".json");
            if (Files.isRegularFile(meta)) {
                final Map<?, ?> was = (Map<?, ?>) Json.parse(Files.readString(meta, StandardCharsets.UTF_8));
                final long received = Files.size(data);
                if (was == null || !String.valueOf(was.get("sha256")).equals(sha256)
                        || number(was.get("bytes")) != bytes) {
                    throw new Refused("HandInInProgress", Map.of("name", name, "received", received, "bytes",
                            was == null ? 0L : number(was.get("bytes"))),
                            "a different file named " + name + " is being handed in");
                }
                if (offset != received) {
                    throw new Refused("PartOutOfOrder", Map.of("name", name, "received", received),
                            "the part starts at " + offset + "; " + received + " bytes of " + name
                                    + " are here");
                }
            } else {
                if (offset != 0) {
                    throw new Refused("PartOutOfOrder", Map.of("name", name, "received", 0L),
                            "the part starts at " + offset + "; nothing of " + name + " is here");
                }
                Files.createDirectories(staging, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
                Files.writeString(meta, Json.write(Map.of("bytes", bytes, "sha256", sha256)),
                        StandardCharsets.UTF_8);
                Files.write(data, new byte[0]);
            }
            final long received = Files.size(data) + part.length;
            if (received > bytes) {
                drop(data, meta);
                throw new Refused("FileDiffers", Map.of("expected", sha256, "actual",
                        "more than " + bytes + " bytes"), name + " is longer than the " + bytes + " bytes it said");
            }
            Files.write(data, part, StandardOpenOption.APPEND);
            if (received < bytes) {
                return new Progress(received, null);
            }
            final String actual = sha256Of(data);
            if (!actual.equals(sha256)) {
                drop(data, meta);
                throw new Refused("FileDiffers", Map.of("expected", sha256, "actual", actual),
                        name + " does not hash to what was said");
            }
            try {
                return new Progress(received, place(container, name, bytes, sha256, data, by));
            } finally {
                drop(data, meta);
            }
        }
    }

    /**
     * Hands a whole file to a running task at once: what the command line on this machine does.
     *
     * @param container The task.
     * @param name Its name in {@code /sokar/files}.
     * @param source The file.
     * @param by Who hands it in.
     * @return The file, in the task.
     * @throws Refused When the name or the size is refused.
     * @throws IOException When the file cannot be read or placed.
     */
    public HandedFile give(final String container, final String name, final Path source, final String by)
            throws IOException {
        checkName(name);
        final long bytes = Files.size(source);
        final long limit = limit(container);
        if (bytes > limit) {
            throw new Refused("FileTooLarge", Map.of("bytes", bytes, "limit", limit),
                    name + " has " + bytes + " bytes; this task takes at most " + limit);
        }
        synchronized (LOCKS.computeIfAbsent(container, any -> new Object())) {
            return place(container, name, bytes, sha256Of(source), source, by);
        }
    }

    /**
     * Takes a handed-in file out of the task again.
     *
     * @param container The task.
     * @param name Its name in {@code /sokar/files}.
     * @param by Who takes it back.
     * @return The file as it was.
     * @throws Refused When the task holds no such file.
     * @throws IOException When it cannot be removed.
     */
    public HandedFile takeBack(final String container, final String name, final String by) throws IOException {
        synchronized (LOCKS.computeIfAbsent(container, any -> new Object())) {
            final HandedFile file = summary(container).files().stream()
                    .filter(each -> each.name().equals(name)).findFirst()
                    .orElseThrow(() -> new Refused("NoSuchFile", Map.of("task", container, "name", name),
                            container + " holds no file " + name + " that was handed in"));
            run(podman.asRootArguments(container, List.of("rm", "-f", Containerfile.HAND_IN_FILES + "/" + name)),
                    null, "Could not remove " + name + " from " + container);
            final HandedFile taken = new HandedFile(name, file.bytes(), file.sha256(), now(), by, file.run());
            append(container, TAKEN_BACK, taken);
            return taken;
        }
    }

    /**
     * Returns every hand-in, replacement and removal for a task name, oldest first: of every run that had it.
     *
     * @param container The task's name.
     * @return The record, empty when nothing was handed in.
     * @throws IOException When it cannot be read.
     */
    public List<Entry> record(final String container) throws IOException {
        final Path file = recordOf(container);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final List<Entry> entries = new ArrayList<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank() && Json.parse(line) instanceof Map<?, ?> map) {
                entries.add(new Entry(String.valueOf(map.get("event")), new HandedFile(
                        String.valueOf(map.get("name")), number(map.get("bytes")),
                        String.valueOf(map.get("sha256")), String.valueOf(map.get("at")),
                        String.valueOf(map.get("by")), String.valueOf(map.get("run")))));
            }
        }
        return entries;
    }

    /**
     * Returns what a task says about its hand-ins: its run, its limit and what it holds now.
     *
     * @param container The task.
     * @return The summary; {@link Summary#none()}'s values where nothing is recorded.
     */
    public Summary summary(final String container) {
        final String run = run(container);
        final Map<String, HandedFile> held = new LinkedHashMap<>();
        try {
            for (final Entry entry : record(container)) {
                if (!entry.file().run().equals(run)) {
                    continue;
                }
                if (TAKEN_BACK.equals(entry.event())) {
                    held.remove(entry.file().name());
                } else {
                    held.put(entry.file().name(), entry.file());
                }
            }
        } catch (IOException | RuntimeException ex) {
            // An unreadable record says nothing is held rather than failing the listing it is a part of.
            held.clear();
        }
        return new Summary(run, limit(container), List.copyOf(held.values()));
    }

    /**
     * Returns the reason a name cannot be a handed-in file's, or nothing when it can.
     *
     * @param name A name somebody chose.
     * @throws Refused When it cannot be one.
     */
    static void checkName(final String name) {
        final String reason;
        if (name.isEmpty()) {
            reason = "it is empty";
        } else if (name.getBytes(StandardCharsets.UTF_8).length > 255) {
            reason = "it is longer than 255 bytes";
        } else if (name.indexOf('/') >= 0) {
            reason = "it contains '/': a handed-in file is a name in /sokar/files, never a path";
        } else if (name.startsWith(".")) {
            reason = "it starts with '.', which the agent may not see as a file it was given";
        } else if (name.codePoints().anyMatch(Character::isISOControl)) {
            reason = "it contains a control character";
        } else {
            return;
        }
        throw new Refused("FileNameRefused", Map.of("name", name, "reason", reason),
                "'" + name + "' cannot be a handed-in file's name: " + reason);
    }

    private HandedFile place(final String container, final String name, final long bytes, final String sha256,
            final Path source, final String by) throws IOException {
        final String incoming = Containerfile.HAND_IN_INCOMING + "/" + name;
        final String target = Containerfile.HAND_IN_FILES + "/" + name;
        final boolean replaced = summary(container).files().stream().anyMatch(each -> each.name().equals(name));
        run(podman.asRootWithInputArguments(container, List.of("dd", "of=" + incoming, "bs=1M", "status=none")),
                source, "Could not write " + name + " into " + container);
        run(podman.asRootArguments(container, List.of("chmod", "0644", incoming)), null,
                "Could not set the permissions of " + name + " in " + container);
        // Renamed into place on the same filesystem: whole or not at all, to an agent that looks at any moment.
        run(podman.asRootArguments(container, List.of("mv", "-f", incoming, target)), null,
                "Could not move " + name + " into place in " + container);
        final HandedFile file = new HandedFile(name, bytes, sha256, now(), by, run(container));
        append(container, replaced ? REPLACED : GIVEN, file);
        return file;
    }

    private void run(final List<String> arguments, final @Nullable Path stdin, final String failure)
            throws IOException {
        if (runner.run(arguments, stdin) != 0) {
            throw new IOException(failure);
        }
    }

    private void append(final String container, final String event, final HandedFile file) throws IOException {
        final Path record = recordOf(container);
        Files.createDirectories(record.getParent(), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", event);
        line.putAll(file.asMap());
        if (!Files.exists(record)) {
            Files.createFile(record, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(record, Json.write(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /** The record, in the state directory like the clearance journal, so it outlives the task. */
    private Path recordOf(final String container) {
        return paths.paths().xdg().state().resolve("hand-ins").resolve(container + ".jsonl");
    }

    /** Where parts wait until the file is complete: the runtime directory, gone at logout like any part. */
    private Path staging(final String container) {
        return paths.containerState(container).resolveSibling("hand-in").resolve(container);
    }

    private String run(final String container) {
        return runOf(paths, container);
    }

    /**
     * Returns the container id of a task's current run, which tells a task from a later one of the same name.
     *
     * @param paths Where tasks keep their state.
     * @param container The task.
     * @return The id; "" when none is recorded.
     */
    static String runOf(final TaskPaths paths, final String container) {
        String id = org.fuin.sokar.wire.Sidecar.recordedId(paths.containerState(container));
        if (id == null) {
            id = org.fuin.sokar.wire.Sidecar.recordedId(paths.taskRecord(container));
        }
        return id == null ? "" : id;
    }

    private long limit(final String container) {
        for (final Path state : List.of(paths.containerState(container), paths.taskRecord(container))) {
            final Path file = state.resolve(LIMIT_FILE);
            try {
                if (Files.isRegularFile(file)) {
                    return Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).strip());
                }
            } catch (IOException | NumberFormatException ex) {
                // Unreadable, it is the default: a limit that cannot be read must not let a larger file in.
                return org.fuin.sokar.core.project.Limits.DEFAULT_HAND_IN;
            }
        }
        return org.fuin.sokar.core.project.Limits.DEFAULT_HAND_IN;
    }

    private void sweep(final Path staging) throws IOException {
        if (!Files.isDirectory(staging)) {
            return;
        }
        final Instant cutoff = clock.instant().minus(ABANDONED);
        try (var parts = Files.list(staging)) {
            for (final Path data : parts.filter(path -> path.toString().endsWith(".part")).toList()) {
                if (Files.getLastModifiedTime(data).toInstant().isBefore(cutoff)) {
                    final String base = data.getFileName().toString();
                    drop(data, staging.resolve(base.substring(0, base.length() - ".part".length()) + ".json"));
                }
            }
        }
    }

    private static long number(final @Nullable Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static void drop(final Path data, final Path meta) throws IOException {
        Files.deleteIfExists(data);
        Files.deleteIfExists(meta);
    }

    private String now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    static String sha256Of(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java has SHA-256", ex);
        }
    }

    private static int process(final List<String> arguments, final @Nullable Path stdin) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder(arguments);
        if (stdin != null) {
            builder.redirectInput(stdin.toFile());
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            return builder.start().waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while " + arguments.get(0) + " ran", ex);
        }
    }
}
