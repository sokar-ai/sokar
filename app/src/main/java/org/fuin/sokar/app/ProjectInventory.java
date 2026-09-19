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
 * What projects this machine knows about, from four sources that each know part of it.
 * <p>
 * The CLI renders this and the daemon serializes it, so "what projects are there" is answered
 * once. Nothing here is a store of its own: a project is not something Sokar creates, it is
 * somebody's directory with a {@code project.yml} in it, and this reports what has been seen of
 * one rather than pretending to own it.
 * <ul>
 * <li>The <strong>follow records</strong> say which projects this machine follows. A project
 *     exists here by being followed, so this is the only source that can name one on a machine
 *     where nothing has run yet - which is every machine on its first day.</li>
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
            @Nullable String mirror, int pending, int tasks, int running, boolean prepared,
            Readiness readiness, org.fuin.sokar.gate.UpstreamDistance.Distance behind,
            List<RepositorySummary> repositories,
            FollowedProjects.@Nullable Followed following) {

        /**
         * Constructor for a project whose image has not been looked for yet.
         *
         * @param name Project name.
         * @param securityClass How much the agent is trusted, or {@code null} when unrecorded.
         * @param file Path of the project file, or {@code null}.
         * @param mirror The gate's mirror, or {@code null}.
         * @param pending Pushes waiting for review.
         * @param tasks How many tasks it has.
         * @param running How many of those are up.
         */
        Summary(String name, @Nullable String securityClass, @Nullable String file,
                @Nullable String mirror, int pending, int tasks, int running) {
            this(name, securityClass, file, mirror, pending, tasks, running, false,
                    Readiness.ABSENT,
                    org.fuin.sokar.gate.UpstreamDistance.Distance.neverChecked(), List.of(), null);
        }

        /**
         * Constructor for a project whose repositories have not been read yet.
         *
         * @param name Project name.
         * @param securityClass How much the agent is trusted, or {@code null}.
         * @param file Path of the project file, or {@code null}.
         * @param mirror The gate's mirror, or {@code null}.
         * @param pending Pushes waiting for review.
         * @param tasks How many tasks it has.
         * @param running How many of those are up.
         * @param prepared Whether an image exists.
         * @param readiness What the image state is.
         * @param behind Last measured upstream distance.
         */
        Summary(String name, @Nullable String securityClass, @Nullable String file,
                @Nullable String mirror, int pending, int tasks, int running, boolean prepared,
                Readiness readiness, org.fuin.sokar.gate.UpstreamDistance.Distance behind) {
            this(name, securityClass, file, mirror, pending, tasks, running, prepared, readiness,
                    behind, List.of(), null);
        }

        /**
         * Returns this project with its image state filled in.
         *
         * @param built Whether an image for it exists.
         * @return A copy.
         */
        Summary prepared(boolean built, Readiness state) {
            return new Summary(name, securityClass, file, mirror, pending, tasks, running, built,
                    state, behind, repositories, following);
        }

        /**
         * Returns this project with the repositories it names filled in.
         * <p>
         * The project's own first, then the ones its file names. Reported rather than left to be
         * inferred from the one mirror a reader can see: a listing that showed one line per
         * project would imply a project is one repository, which is what it is not.
         *
         * @param named What the project file says.
         * @return A copy.
         */
        Summary repositories(List<RepositorySummary> named) {
            return new Summary(name, securityClass, file, mirror, pending, tasks, running,
                    prepared, readiness, behind, named, following);
        }

        /**
         * Returns this project with its follow state filled in.
         *
         * @param state What this account recorded, or {@code null} when it follows nothing here.
         * @return A copy.
         */
        Summary following(FollowedProjects.@Nullable Followed state) {
            return new Summary(name, securityClass, file, mirror, pending, tasks, running,
                    prepared, readiness, behind, repositories, state);
        }

        /**
         * Returns this project with its last measured upstream distance filled in.
         *
         * @param distance What was last measured.
         * @return A copy.
         */
        Summary behind(org.fuin.sokar.gate.UpstreamDistance.Distance distance) {
            return new Summary(name, securityClass, file, mirror, pending, tasks, running,
                    prepared, readiness, distance);
        }

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
            // Whether a task can start here without building an image first. Not a claim that the
            // image matches the project file as it stands now - only that one is there.
            map.put("prepared", prepared);
            map.put("preparedState", readiness.name());
            // Three fields, not one. A bare number would have to be shown as though it were
            // current, and the only thing worse than a stale answer is a stale answer that looks
            // fresh - so the age travels with it, and the reason says whether there is a number
            // at all.
            map.put("behind", behind.behind());
            map.put("behindMeasured",
                    behind.measured() == null ? "" : behind.measured().toString());
            map.put("behindReason", behind.reason().name());
            map.put("behindDetail", behind.detail() == null ? "" : behind.detail());
            // The repositories themselves, not a count and not just names: a client offering the
            // choice at task start needs the names, and a project view needs each one's own
            // mirror, pending count and distance. A number would send it back to read the file.
            map.put("repositories", repositories.stream().map(RepositorySummary::asMap).toList());
            // Empty for a project this machine does not follow, which is the ordinary case. A
            // project view reads its follow state from here rather than joining a second call.
            map.put("following", following == null ? Map.<String, Object>of()
                    : followAsMap(following));
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

        // Read first, because a followed project is in this list from the moment the follow is
        // taken - before any mirror, task or registry entry exists. Found by Agent Frontend:
        // their dialog followed a project, the follow applied, and the tree stayed empty.
        final Map<String, FollowedProjects.Followed> followed = new LinkedHashMap<>();
        try {
            new FollowedProjects(context.paths().followed()).all()
                    .forEach(one -> followed.put(one.name(), one));
        } catch (final java.io.IOException ex) {
            // A listing is not the place to fail over the follow record. Every project then
            // answers "not followed", which reads as what it is rather than as an error here.
            followed.clear();
        }

        final Map<String, String> files = new ProjectRegistry(context.paths().projectRegistry())
                .all();
        final Map<String, Summary> found = new LinkedHashMap<>();

        for (final String name : followed.keySet()) {
            found.put(name, new Summary(name, null, fileOf(name), null, 0, 0, 0));
        }
        for (final String name : mirroredNames()) {
            found.put(name, new Summary(name, null, fileOf(name),
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
                    fileOf(task.project()),
                    known == null ? null : known.mirror(),
                    known == null ? 0 : known.pending(),
                    (known == null ? 0 : known.tasks()) + 1,
                    (known == null ? 0 : known.running()) + (task.running() ? 1 : 0)));
        }
        // A project whose file was recorded but that has neither a mirror nor a task: it ran once
        // and everything was removed. Still worth showing - the file is what an interface acts on.
        files.keySet().stream().filter(name -> !found.containsKey(name)).forEach(name ->
                found.put(name, new Summary(name, null, fileOf(name), null, 0, 0, 0)));

        // Asked once for every project rather than once per project: 'podman image exists' is a
        // subprocess, and this list is re-read after every task start and every approval.
        final java.util.Set<String> images = context.podman().sokarImages();
        // Read, never measured: measuring reaches the network, and this list is re-read after
        // every task start and every approval. What is read here was measured on a timer and
        // carries the moment it was taken.
        final UpstreamRecords upstream = new UpstreamRecords(context.paths().upstreamRecords());
        return found.values().stream()
                .sorted(java.util.Comparator.comparing(Summary::name))
                .map(summary -> {
                    final boolean built = images.contains("sokar/" + summary.name());
                    return summary.prepared(built, readiness(context, summary.name(),
                            summary.file() == null ? null : java.nio.file.Path.of(summary.file()),
                            built)).behind(upstream.get(summary.name()))
                            .repositories(repositoriesOf(summary, upstream))
                            .following(followed.get(summary.name()));
                })
                .toList();
    }

    /**
     * One repository of a project, and the state that belongs to it rather than to the project.
     * <p>
     * <strong>A list of these rather than parallel lists.</strong> Four lists indexed together
     * drift the moment one of them is built from a different source, and the drift is silent -
     * a mirror reported against the wrong repository reads as an answer.
     *
     * @param name What {@code --repository} takes.
     * @param own Whether this is the project's own repository - where the project file, the
     *        planning and the issues live. Reported rather than left to be worked out by comparing
     *        the name to the project's, which is a rule a reader would have to know.
     * @param upstream Where approved work goes, or "" when it has none. A repository without one
     *        is one whose work stays on this machine.
     * @param mirror The bare mirror on this machine, or "" when it has never been used.
     * @param pending How many pushes are waiting for review in that mirror.
     * @param behind How far this repository is from its upstream, as last measured.
     */
    public record RepositorySummary(String name, boolean own, String upstream, String mirror,
            int pending, org.fuin.sokar.gate.UpstreamDistance.Distance behind,
            List<Grant> egress, ResolvedLimits limits) {

        /**
         * Constructor for a repository reported before its egress and limits were.
         *
         * @param name Repository name.
         * @param own Whether it is the project's own.
         * @param upstream Where approved work goes, or "".
         * @param mirror Its mirror, or "".
         * @param pending Pushes waiting for review.
         * @param behind Last measured distance from its upstream.
         */
        RepositorySummary(String name, boolean own, String upstream, String mirror, int pending,
                org.fuin.sokar.gate.UpstreamDistance.Distance behind) {
            this(name, own, upstream, mirror, pending, behind, List.of(),
                    new ResolvedLimits("", "", 0, "", "", ""));
        }

        /**
         * Returns this as plain values, for a caller that has to put it on a wire.
         *
         * @return The repository.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("own", own);
            map.put("upstream", upstream);
            map.put("mirror", mirror);
            map.put("pending", pending);
            // The same three fields the project carries, for the same reason: a bare number would
            // have to be shown as though it were current.
            map.put("behind", behind.behind());
            map.put("behindMeasured",
                    behind.measured() == null ? "" : behind.measured().toString());
            map.put("behindReason", behind.reason().name());
            map.put("behindDetail", behind.detail() == null ? "" : behind.detail());
            map.put("egress", egress.stream().map(Grant::asMap).toList());
            map.put("limits", limits.asMap());
            return map;
        }
    }

    /**
     * Returns the repositories a project names, its own first.
     *
     * @param summary The project, which may have no readable file.
     * @return The names, empty when the file cannot be read - which is not the same as a project
     *         with one repository, and is why an unreadable file yields nothing rather than a
     *         guess at the project's own name.
     */
    private List<RepositorySummary> repositoriesOf(Summary summary, UpstreamRecords upstream) {
        if (summary.file() == null) {
            return List.of();
        }
        final org.fuin.sokar.core.project.Project project;
        try {
            project = GateSupport.project(java.nio.file.Path.of(summary.file()));
        } catch (final RuntimeException ex) {
            return List.of();
        }
        final List<RepositorySummary> found = new java.util.ArrayList<>();
        for (final org.fuin.sokar.core.project.Repository repository
                : project.allRepositories()) {
            final Path mirror = GateSupport.mirror(project, repository);
            found.add(new RepositorySummary(repository.name(),
                    repository.name().equals(project.name()),
                    repository.upstream() == null ? "" : repository.upstream(),
                    Files.isDirectory(mirror) ? mirror.toString() : "",
                    pendingInMirror(mirror),
                    upstream.get(UpstreamRecords.key(project.name(), repository.name())),
                    grantsOf(project, repository), limitsOf(project, repository)));
        }
        return List.copyOf(found);
    }

    /**
     * Returns everything a task on one repository may reach, each saying which block granted it.
     *
     * @param project The project.
     * @param repository One of its repositories.
     * @return The grants, the project's first.
     */
    private static List<Grant> grantsOf(final org.fuin.sokar.core.project.Project project,
            final org.fuin.sokar.core.project.Repository repository) {
        final List<Grant> grants = new java.util.ArrayList<>();
        final org.fuin.sokar.core.project.Egress effective = project.egressFor(repository);
        for (final String set : effective.sets()) {
            grants.add(new Grant(set, "set",
                    project.egress().sets().contains(set) ? "project" : "repository"));
        }
        for (final String domain : effective.domains()) {
            grants.add(new Grant(domain, "domain",
                    project.egress().domains().contains(domain) ? "project" : "repository"));
        }
        return List.copyOf(grants);
    }

    /**
     * Returns what a task on one repository may consume, and where each key came from.
     *
     * @param project The project.
     * @param repository One of its repositories.
     * @return The limits in force for it.
     */
    private static ResolvedLimits limitsOf(final org.fuin.sokar.core.project.Project project,
            final org.fuin.sokar.core.project.Repository repository) {
        final org.fuin.sokar.core.project.Limits limits = project.limitsFor(repository);
        final org.fuin.sokar.core.project.Limits.Declared own = repository.limits();
        return new ResolvedLimits(
                limits.memory() == null ? "" : limits.memory(),
                limits.cpus() == null ? "" : limits.cpus(),
                limits.pids(),
                own.memory() == null ? "project" : "repository",
                own.cpus() == null ? "project" : "repository",
                own.pids() == null ? "project" : "repository");
    }

    /**
     * Counts what is waiting in one mirror.
     *
     * @param mirror The bare mirror, which need not exist.
     * @return How many pushes are waiting, zero when it cannot be asked.
     */
    private int pendingInMirror(Path mirror) {
        if (!Files.isDirectory(mirror)) {
            return 0;
        }
        try {
            return new GitGate(context.runner(), mirror, GateMode.GATEKEEPING, null)
                    .pending().size();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /**
     * One thing a repository may reach, and which block granted it.
     * <p>
     * One list with a source on each entry rather than two lists to subtract from one another: a
     * repository's grants are <em>added</em> to the project's, and a screen showing both halves
     * should not have to work out which is which.
     *
     * @param value The set name or the host.
     * @param kind {@code set} or {@code domain}.
     * @param from {@code project} or {@code repository}.
     */
    public record Grant(String value, String kind, String from) {

        /**
         * Returns this as plain values.
         *
         * @return The grant.
         */
        public Map<String, Object> asMap() {
            return Map.of("value", value, "kind", kind, "from", from);
        }
    }

    /**
     * What a task on one repository may consume, and where each key came from.
     * <p>
     * A repository's limits <em>replace</em> the project's key by key, so the interesting part is
     * which key it replaced. {@code memoryFrom} and its two siblings say {@code repository} when
     * that repository declared the key and {@code project} otherwise - and {@code project} covers
     * both a value the project file wrote and Sokar's own default, which this side cannot tell
     * apart today.
     *
     * @param memory Value for the memory limit, or "" for no limit.
     * @param cpus Value for the CPU limit, or "" for no limit.
     * @param pids Process limit.
     * @param memoryFrom Which block the memory limit came from.
     * @param cpusFrom Which block the CPU limit came from.
     * @param pidsFrom Which block the process limit came from.
     */
    public record ResolvedLimits(String memory, String cpus, int pids, String memoryFrom,
            String cpusFrom, String pidsFrom) {

        /**
         * Returns this as plain values.
         *
         * @return The limits.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("memory", memory);
            map.put("cpus", cpus);
            map.put("pids", pids);
            map.put("memoryFrom", memoryFrom);
            map.put("cpusFrom", cpusFrom);
            map.put("pidsFrom", pidsFrom);
            return map;
        }
    }

    /**
     * Returns one project's follow state as plain values, for a caller that has to put it on a
     * wire.
     * <p>
     * <strong>One rendering, used by both `Following` and `Projects`.</strong> A project view
     * asking "is what I am showing in force" and an account's list of what it follows are
     * different questions, and they must not answer them from two mappings that can drift.
     *
     * @param followed What was recorded.
     * @return The state.
     */
    public static Map<String, Object> followAsMap(final FollowedProjects.Followed followed) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", followed.name());
        map.put("url", followed.url());
        map.put("commit", followed.commit());
        map.put("at", followed.at());
        map.put("outcome", followed.outcome());
        map.put("detail", followed.detail());
        map.put("refused", followed.refused());
        map.put("signer", followed.signer());
        // Reported wherever the project is, because it is a state rather than an error: without an
        // anchor, whoever may push to that repository decides what tasks here may reach.
        map.put("unverified", followed.unverified());
        // Derived from the outcome rather than stored, so the two cannot come to disagree - and
        // so an interface does not have to keep a list of which outcomes are stuck in step with
        // ours.
        map.put("needsAPerson", followed.needsAPerson());
        return map;
    }

    /** What an interface can say about a project's task image. */
    public enum Readiness {

        /** No image. The first task here spends minutes building before anything happens. */
        ABSENT,

        /** An image built from this project file as it stands. */
        READY,

        /**
         * An image built before the project file changed under it.
         * <p>
         * The state that could not be answered at all until the image began recording what it was
         * built from: work would start, run in something that was not what the file describes, and
         * nothing would say so.
         */
        STALE,

        /**
         * An image exists and does not say what it was built from.
         * <p>
         * Built by a Sokar that wrote no recipe label. Not stale - nothing here knows either way -
         * and reporting it as stale would send somebody rebuilding for no reason.
         */
        UNKNOWN
    }

    /**
     * Says what this project's image is, for a project whose file can still be read.
     *
     * @param context The machine.
     * @param name Project name.
     * @param file The project file, or {@code null} when it is not there any more.
     * @param exists Whether an image for it exists at all.
     * @return What an interface can say.
     */
    static Readiness readiness(SokarContext context, String name,
            java.nio.file.@Nullable Path file, boolean exists) {
        if (!exists) {
            return Readiness.ABSENT;
        }
        if (file == null) {
            // The image is there and the file that would say whether it is current is not.
            return Readiness.UNKNOWN;
        }
        final java.util.Optional<String> recorded =
                context.podman().imageRecipe("sokar/" + name);
        if (recorded.isEmpty()) {
            return Readiness.UNKNOWN;
        }
        try {
            final org.fuin.sokar.core.project.Project project =
                    org.fuin.sokar.core.project.ProjectReader.read(file);
            return recorded.get().equals(
                    org.fuin.sokar.runtime.Containerfile.fingerprint(project))
                    ? Readiness.READY : Readiness.STALE;
        } catch (RuntimeException ex) {
            // A file that cannot be read cannot say the image is out of date.
            return Readiness.UNKNOWN;
        }
    }

    /**
     * Returns the path of a project's file, when there is one and it is still there.
     * <p>
     * Asked of {@link ProjectSource}, which is the single answer to where a project's file comes
     * from: a followed project's verified clone beats whatever a task last recorded, and a
     * followed project with nothing in force has no file at all rather than a stale one. A file
     * that has moved is reported as absent rather than as a path nothing can read - an interface
     * that called a gate method with it would be refused for a reason that looks like a bug in
     * the daemon.
     */
    @Nullable
    private String fileOf(final String name) {
        final Path file = ProjectSource.resolve(context, name).file();
        return file == null ? null : file.toString();
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
    /**
     * Counts what is waiting for review across every repository of a project.
     * <p>
     * <strong>Every mirror, not the project's own.</strong> A project is a unit of work over one or
     * more repositories, and each keeps its own mirror; counting only the first would report a
     * number that silently excludes repositories - worse than reporting none, because a number
     * that is there is read as the answer. Found by the interface, which asked whether this
     * already counted them all.
     *
     * @param name Project name.
     * @return How many pushes are waiting, across all of its mirrors.
     */
    private int pendingIn(String name) {
        int waiting = 0;
        for (final Path mirror : mirrorsOf(name)) {
            try {
                waiting += new GitGate(context.runner(), mirror, GateMode.GATEKEEPING, null)
                        .pending().size();
            } catch (RuntimeException ex) {
                // A directory that is not a repository, or a git that would not run. Neither is
                // worth failing a listing for, and neither is worth losing the other mirrors over.
                continue;
            }
        }
        return waiting;
    }

    /**
     * Returns every mirror a project has, its own repository's first.
     * <p>
     * Read from disk rather than from the project file, for the reason the deletion path reads it
     * that way: a mirror holds pushes nobody has reviewed, and it outlives the file that named it.
     *
     * @param name Project name.
     * @return Existing bare mirrors, in a fixed order.
     */
    private List<Path> mirrorsOf(String name) {
        final Path mirrors = context.paths().xdg().data().resolve("mirrors");
        final List<Path> found = new java.util.ArrayList<>();
        final Path own = mirrorOf(name);
        if (Files.isDirectory(own)) {
            found.add(own);
        }
        final Path named = mirrors.resolve(name);
        if (Files.isDirectory(named)) {
            try (java.util.stream.Stream<Path> entries = Files.list(named)) {
                found.addAll(entries.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().endsWith(".git"))
                        .sorted(java.util.Comparator.comparing(Path::toString)).toList());
            } catch (java.io.IOException ex) {
                // The project's own mirror is still an answer, and a listing is not the place to
                // fail over a directory that cannot be read.
                return List.copyOf(found);
            }
        }
        return List.copyOf(found);
    }
}
