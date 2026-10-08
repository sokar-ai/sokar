package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.fuin.sokar.runtime.ContainerName;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultException;
import org.fuin.sokar.vault.VaultFile;
import org.jspecify.annotations.Nullable;

/**
 * Finds what nothing on this machine owns any more, and removes it when asked.
 * <p>
 * <strong>Orphaned means, and only means:</strong> a project that is not followed, has no running task, and whose
 * project file is missing or unreadable - with everything kept for it, its stopped tasks included; what is kept for a
 * task whose container is gone; and an image, a build context or a record named after a project that exists nowhere
 * any more. A running task and a followed project are never orphaned, whatever else is wrong with them.
 * <p>
 * <strong>Nothing that holds work is removed without being asked twice.</strong> A mirror with a push nobody
 * reviewed, a stopped task whose workspace may hold work, a mailbox with a message held, refused or not yet sent: each
 * is listed with what it holds and kept, unless {@code includingWork} says otherwise. A project goes through the same
 * {@link ProjectDeletion} {@code unfollow} uses, so the two refuse and remove alike.
 * <p>
 * <strong>The clearance journal stays:</strong> it is the record of what a task was allowed to reach, kept on
 * purpose when a task is removed.
 */
public final class Prune {

    /** The login image and its build context: an agent's, never a project's. */
    static final String LOGIN = "sokar-login";

    /**
     * How long what is kept for a task must have been left alone before it counts as left over. A task's mailbox and
     * state are made before its container is, so a prune while a task starts would otherwise take them.
     */
    static final java.time.Duration SETTLED = java.time.Duration.ofMinutes(15);

    /**
     * One thing found.
     *
     * @param kind {@code PROJECT}, {@code TASK}, {@code IMAGE}, {@code BUILD}, {@code RECORD} or {@code VAULT}.
     * @param name What it is called: a project, a container, an image, a path or a vault entry.
     * @param what What of it goes, or would go, as a person recognises it.
     * @param holds What it holds that nobody has looked at, or "" when nothing; non-empty means it is kept.
     */
    public record Item(String kind, String name, List<String> what, String holds) {

        /**
         * Constructor with a defensive copy.
         *
         * @param kind What it is.
         * @param name What it is called.
         * @param what What of it goes.
         * @param holds What it holds, or "".
         */
        public Item {
            what = List.copyOf(what);
        }

        /**
         * Returns this as plain values, for a wire.
         *
         * @return The item.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("name", name);
            map.put("what", what);
            map.put("holds", holds);
            return map;
        }
    }

    /**
     * What a prune found, and did.
     *
     * @param applied Whether anything was removed, or this is what would be.
     * @param removes What goes, or went.
     * @param keeps What was found and kept because it holds work, or could not be removed.
     * @param unchecked What could not be looked at, and why.
     */
    public record Result(boolean applied, List<Item> removes, List<Item> keeps, List<String> unchecked) {

        /**
         * Constructor with defensive copies.
         *
         * @param applied Whether anything was removed.
         * @param removes What goes.
         * @param keeps What is kept.
         * @param unchecked What was not looked at.
         */
        public Result {
            removes = List.copyOf(removes);
            keeps = List.copyOf(keeps);
            unchecked = List.copyOf(unchecked);
        }

        /**
         * Returns whether anything at all was found.
         *
         * @return {@code true} when something goes or is kept.
         */
        public boolean found() {
            return !removes.isEmpty() || !keeps.isEmpty();
        }

        /**
         * Returns this as plain values, for a wire.
         *
         * @return The result.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("applied", applied);
            map.put("removes", removes.stream().map(Item::asMap).toList());
            map.put("keeps", keeps.stream().map(Item::asMap).toList());
            map.put("unchecked", unchecked);
            return map;
        }
    }

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context This machine.
     */
    public Prune(final SokarContext context) {
        this.context = context;
    }

    /**
     * Finds what nothing owns any more, and removes it when {@code apply} says so.
     *
     * @param apply Remove it; otherwise only say what would go.
     * @param includingWork Remove what holds work too.
     * @return What was found, and what became of it.
     */
    public Result run(final boolean apply, final boolean includingWork) {
        final List<Item> removes = new ArrayList<>();
        final List<Item> keeps = new ArrayList<>();
        final List<String> unchecked = new ArrayList<>();

        final List<ProjectInventory.Summary> projects = new ProjectInventory(context).projects();
        final Set<String> running = new LinkedHashSet<>();
        for (final TaskInventory.Task task : new TaskInventory(context).tasks()) {
            if (task.running() && task.project() != null) {
                running.add(task.project());
            }
        }
        final Set<String> remaining = new TreeSet<>();
        for (final ProjectInventory.Summary project : projects) {
            if (!orphaned(project, running)) {
                remaining.add(project.name());
                continue;
            }
            final Item item = project(project.name(), apply, includingWork);
            if (item.holds().isEmpty()) {
                removes.add(item);
            } else {
                keeps.add(item);
                remaining.add(project.name());
            }
        }

        final Set<String> containers = new LinkedHashSet<>(context.podman().sokarContainers());
        for (final String container : goneTasks(containers)) {
            final Item item = task(container, apply, includingWork);
            (item.holds().isEmpty() ? removes : keeps).add(item);
        }
        // What a project's removal already named is not named again on its own.
        final Set<String> named = new java.util.HashSet<>();
        removes.stream().filter(item -> "PROJECT".equals(item.kind())).forEach(item -> named.addAll(item.what()));
        images(remaining, named, apply, removes, keeps);
        records(remaining, named, apply, removes, keeps);
        vault(containers, remaining, apply, removes, unchecked);
        return new Result(apply, removes, keeps, unchecked);
    }

    /**
     * Returns whether a project is orphaned: not followed, no task of it running, and no file anybody can read.
     *
     * @param project The project as the inventory lists it.
     * @param running The projects that have a running task.
     * @return {@code true} when nothing owns it any more.
     */
    static boolean orphaned(final ProjectInventory.Summary project, final Set<String> running) {
        return !DefaultProject.is(project.name()) && project.following() == null && !running.contains(project.name())
                && (project.file() == null || project.repositories().isEmpty());
    }

    private Item project(final String name, final boolean apply, final boolean includingWork) {
        final List<String> what = new ArrayList<>();
        // What 'remove' would refuse for a stopped task, asked before rather than learnt from a refusal halfway:
        // a preview has to say what the real run will do.
        // Grouped by what is held, so nine tasks nobody recorded are one sentence and nine names, not nine sentences.
        final Map<String, List<String>> work = new LinkedHashMap<>();
        for (final TaskInventory.Task task : new TaskInventory(context).tasks()) {
            if (name.equals(task.project())) {
                final String held = workspaceOf(task.name());
                if (!held.isEmpty()) {
                    work.computeIfAbsent(held, reason -> new ArrayList<>()).add(task.name());
                }
            }
        }
        if (!work.isEmpty() && !includingWork) {
            final List<String> said = new ArrayList<>();
            final List<String> tasks = new ArrayList<>();
            work.forEach((reason, names) -> {
                said.add(names.size() == 1 ? "a stopped task: " + reason
                        : names.size() + " stopped tasks: " + reason);
                tasks.addAll(names);
            });
            return new Item("PROJECT", name, tasks, String.join("; ", said));
        }
        final ProjectDeletion.Result result = new ProjectDeletion(context).delete(name, !apply, includingWork);
        result.removes().forEach(removal -> what.add(removal.kind().toLowerCase(java.util.Locale.ROOT)
                .replace('_', ' ') + " " + removal.what()));
        return switch (result.outcome()) {
            case DELETED, PREVIEWED -> new Item("PROJECT", name, what, "");
            case HOLDS_WORK -> new Item("PROJECT", name, what, result.unreviewed().isEmpty()
                    ? String.valueOf(result.detail()) : "pushes nobody reviewed: " + String.join(", ", result.unreviewed()));
            default -> new Item("PROJECT", name, what, "not removed: " + result.detail());
        };
    }

    /**
     * Says what a stopped task's workspace may hold, as {@code task remove} judges it.
     *
     * @param container The task.
     * @return "" when it is known to hold nothing; otherwise what.
     */
    private String workspaceOf(final String container) {
        final Path state = context.paths().tasks().containerState(container);
        final UnhandedWork.Held held = UnhandedWork.read(Files.isDirectory(state) ? state
                : context.paths().tasks().taskRecord(container));
        if (!held.readable()) {
            return "no record of what their workspace holds";
        }
        final String phrase = held.phrase();
        return phrase == null ? "" : "their workspace holds " + phrase;
    }

    /**
     * Returns every task something is kept for while its container is gone.
     *
     * @param containers The containers that exist.
     * @return Container names, sorted.
     */
    private Set<String> goneTasks(final Set<String> containers) {
        final Set<String> gone = new TreeSet<>();
        for (final Path directory : List.of(directoryOf(context.paths().tasks().taskRecord("x")),
                directoryOf(context.paths().messaging().mailbox("x")), directoryOf(context.paths().tasks().sessionRecord("x")),
                directoryOf(context.paths().tasks().containerState("x")))) {
            for (final String name : names(directory)) {
                if (ContainerName.isTask(name) && !containers.contains(name)) {
                    gone.add(name);
                }
            }
        }
        gone.removeIf(name -> !settled(name));
        return gone;
    }

    /**
     * Returns whether nothing kept for a task has changed for {@link #SETTLED}.
     *
     * @param container The task.
     * @return {@code true} when it has been left alone long enough to be left over.
     */
    private boolean settled(final String container) {
        final java.time.Instant before = java.time.Instant.now().minus(SETTLED);
        for (final Path path : List.of(context.paths().tasks().taskRecord(container), context.paths().messaging().mailbox(container),
                context.paths().tasks().sessionRecord(container), context.paths().tasks().containerState(container))) {
            if (!Files.exists(path)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(path)) {
                if (tree.anyMatch(each -> modified(each).isAfter(before))) {
                    return false;
                }
            } catch (IOException | java.io.UncheckedIOException ex) {
                return false;
            }
        }
        return true;
    }

    private static java.time.Instant modified(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ex) {
            // What cannot be read counts as changed just now: when in doubt, it is not left over.
            return java.time.Instant.now();
        }
    }

    private Item task(final String container, final boolean apply, final boolean includingWork) {
        final List<String> what = new ArrayList<>();
        if (Files.isDirectory(context.paths().tasks().taskRecord(container))) {
            what.add("its saved state");
        }
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        if (mailbox.exists()) {
            what.add("its mailbox");
        }
        if (Files.exists(context.paths().tasks().sessionRecord(container))) {
            what.add("its agent session");
        }
        if (Files.isDirectory(context.paths().tasks().containerState(container))) {
            what.add("its runtime directory");
        }
        final String held = mailbox.exists() ? mailWork(mailbox) : "";
        if (!held.isEmpty() && !includingWork) {
            return new Item("TASK", container, what, held);
        }
        if (apply) {
            try {
                new TaskState(context).forget(container);
                new TaskSession(context).forget(container);
                deleteTree(context.paths().tasks().containerState(container));
                mailbox.delete();
                new TaskGuide(context.paths(), container).delete();
            } catch (IOException | RuntimeException ex) {
                return new Item("TASK", container, what, "not removed: " + ex.getMessage());
            }
        }
        return new Item("TASK", container, what, "");
    }

    /**
     * Says what a mailbox holds that nobody has decided about or that has not gone out yet.
     *
     * @param mailbox The mailbox.
     * @return "" when nothing; otherwise what.
     */
    static String mailWork(final Mailbox mailbox) {
        final List<String> said = new ArrayList<>();
        try {
            final long decided = MessageLookup.all(mailbox).size();
            if (decided > 0) {
                said.add(decided + " message(s) held or refused, waiting for a person");
            }
        } catch (IOException ex) {
            said.add("messages that could not be read: " + ex.getMessage());
        }
        long unsent = count(mailbox.outboxNew()) + count(mailbox.incoming()) + count(mailbox.inbound())
                + count(mailbox.person());
        for (final String transport : names(mailbox.queue("x").getParent())) {
            unsent += count(mailbox.queueActive(transport)) + count(mailbox.queueDeferred(transport));
        }
        if (unsent > 0) {
            said.add(unsent + " message(s) not delivered yet");
        }
        return String.join(" and ", said);
    }

    private void images(final Set<String> remaining, final Set<String> named, final boolean apply,
            final List<Item> removes, final List<Item> keeps) {
        for (final String image : context.podman().sokarImages()) {
            final String project = image.substring("sokar/".length());
            if (!LOGIN.equals(project) && !remaining.contains(project) && !named.contains("image " + image)) {
                if (apply) {
                    context.podman().removeImage(image);
                }
                removes.add(new Item("IMAGE", image, List.of("the image"), ""));
            }
        }
        final Path builds = directoryOf(context.paths().tasks().buildContext("x"));
        for (final String project : names(builds)) {
            if (!LOGIN.equals(project) && !remaining.contains(project)
                    && !named.contains("build " + builds.resolve(project))) {
                remove(builds.resolve(project), "BUILD", "its build context", apply, removes, keeps);
            }
        }
    }

    /**
     * Removes what is recorded for a project that exists nowhere any more: upstream distances, moderation, and a
     * transport's state for it.
     */
    private void records(final Set<String> remaining, final Set<String> named, final boolean apply,
            final List<Item> removes, final List<Item> keeps) {
        final Path upstream = context.paths().projects().upstreamRecords();
        for (final String file : names(upstream)) {
            // '<project>' or '<project>.<repository>'; a project's name has no dot.
            final String project = file.contains(".") ? file.substring(0, file.indexOf('.')) : file;
            if (!remaining.contains(project) && !named.contains("upstream record " + upstream.resolve(file))) {
                remove(upstream.resolve(file), "RECORD", "how far it was from its upstream", apply, removes, keeps);
            }
        }
        sweep(context.paths().messaging().moderation("x").getParent(), ".json", "how its peers were moderated", remaining,
                apply, removes, keeps);
        // A clone left beside no follow record: a refused first follow raced by the daemon's pass made one.
        sweep(context.paths().projects().followedClone("x").getParent(), ".git", "the clone it was followed into", remaining,
                apply, removes, keeps);
        final Path transports = context.paths().xdg().state().resolve("transport");
        for (final String scheme : names(transports)) {
            final Path root = transports.resolve(scheme);
            final String what = "the " + scheme + " conversation's record of it";
            // <project>.json beside the three directories, each of which is keyed by project too.
            sweep(root, ".json", what, remaining, apply, removes, keeps);
            sweep(root.resolve("members"), ".json", what, remaining, apply, removes, keeps);
            sweep(root.resolve("addresses"), ".json", what, remaining, apply, removes, keeps);
            sweep(root.resolve("inbound"), "", what, remaining, apply, removes, keeps);
        }
    }

    /**
     * Removes each entry of a directory named after a project that does not remain.
     *
     * @param directory Where the entries are.
     * @param suffix What follows the project's name in an entry's name; "" for an entry named by it alone. An entry
     *        without it is not one of these and is left alone.
     */
    private static void sweep(final @Nullable Path directory, final String suffix, final String what,
            final Set<String> remaining, final boolean apply, final List<Item> removes, final List<Item> keeps) {
        if (directory == null) {
            return;
        }
        for (final String entry : names(directory)) {
            final Path path = directory.resolve(entry);
            final boolean shaped = suffix.isEmpty() ? Files.isDirectory(path)
                    : entry.endsWith(suffix) && (Files.isRegularFile(path) || ".git".equals(suffix));
            if (shaped && !remaining.contains(entry.substring(0, entry.length() - suffix.length()))) {
                remove(path, "RECORD", what, apply, removes, keeps);
            }
        }
    }

    /**
     * Removes the vault entries kept for a task that is gone or a project that is: a task's tokens and a transport's
     * secrets for either. A locked vault is said, not guessed at.
     */
    private void vault(final Set<String> containers, final Set<String> remaining, final boolean apply,
            final List<Item> removes, final List<String> unchecked) {
        if (!context.vault().exists()) {
            return;
        }
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty()) {
            unchecked.add("the vault is locked, so what it keeps for tasks and projects that are gone was not looked"
                    + " at; 'sokar vault unlock' and ask again");
            return;
        }
        final Set<String> gone = new TreeSet<>();
        try {
            for (final String name : context.vault().read(opener.get()).keySet()) {
                if (orphanedEntry(name, containers, remaining)) {
                    gone.add(name);
                }
            }
            if (apply && !gone.isEmpty()) {
                context.vault().update(opener.get(), entries -> {
                    final Map<String, VaultEntry> updated = new LinkedHashMap<>(entries);
                    updated.keySet().removeAll(gone);
                    return updated;
                });
            }
        } catch (VaultException ex) {
            unchecked.add("the vault could not be read or written: " + ex.getMessage());
            return;
        }
        gone.forEach(name -> removes.add(new Item("VAULT", name, List.of("the vault entry"), "")));
    }

    /**
     * Returns whether a vault entry belongs to a task or project that is gone.
     *
     * @param name The entry.
     * @param containers The containers that exist.
     * @param remaining The projects that stay.
     * @return {@code true} for {@code task/<gone>/…}, {@code transport/<scheme>/task/<gone>} and
     *         {@code transport/<scheme>/project/<gone>}.
     */
    static boolean orphanedEntry(final String name, final Set<String> containers, final Set<String> remaining) {
        if (name.startsWith(TaskSecrets.PREFIX)) {
            return !containers.contains(TaskSecrets.owner(name));
        }
        if (name.startsWith(TaskSecrets.TRANSPORT_PREFIX)) {
            final String[] parts = name.split("/");
            if (parts.length == 4 && "task".equals(parts[2])) {
                return !containers.contains(parts[3]);
            }
            if (parts.length == 4 && "project".equals(parts[2])) {
                return !remaining.contains(parts[3]);
            }
        }
        return false;
    }

    private static void remove(final Path path, final String kind, final String what, final boolean apply,
            final List<Item> removes, final List<Item> keeps) {
        if (apply) {
            try {
                deleteTree(path);
            } catch (IOException ex) {
                // Kept, and said: the next prune finds it again.
                keeps.add(new Item(kind, path.toString(), List.of(what), "not removed: " + ex.getMessage()));
                return;
            }
        }
        removes.add(new Item(kind, path.toString(), List.of(what), ""));
    }

    /**
     * Returns the directory every task's or project's entry of one kind sits in, from the path of one of them.
     *
     * @param one The path of an entry, for any name.
     * @return Its directory.
     */
    private static Path directoryOf(final Path one) {
        return java.util.Objects.requireNonNull(one.getParent(), one::toString);
    }

    private static List<String> names(final @Nullable Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private static long count(final Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            // Messages only: a fresh mailbox has directories inside these, and they are not mail.
            return entries.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(".")).count();
        } catch (IOException ex) {
            return 0;
        }
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            for (final Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
