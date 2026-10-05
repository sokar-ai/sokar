package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.project.Repository;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The project {@code default}: where work on a repository goes that no followed project names.
 * <p>
 * <strong>The one project that does not come from a repository</strong> (decided by the operator on 2026-10-01): every
 * machine has it, it is kept on this machine alone, and no repository, signature or follow is involved. Its settings
 * are Sokar's and cannot be changed - security class {@code guarded}, the default base image, no egress beyond what a
 * task's agent needs, no conversation. Whoever needs anything else makes a project repository and follows it.
 * <p>
 * <strong>What a person adds is only which repositories are in it.</strong> They are kept in a record of this
 * machine's own; the project file every other part of Sokar reads is written from that record and Sokar's settings
 * each time it is asked for, so an edit to it never lasts and nothing a task or a forge can write reaches it.
 */
public final class DefaultProject {

    /** Its name, fixed and reserved: no followed project may take it. */
    public static final String NAME = "default";

    /** The base image a task in it runs on. */
    static final String BASE_IMAGE = "ubuntu:24.04";

    /**
     * One repository in it.
     *
     * @param name What {@code --repository} takes.
     * @param upstream Where approved work goes: the checkout's {@code origin}, or a forge's address.
     * @param checkout The checkout it was added from, or "" when it was named or picked in an interface.
     */
    public record Entry(String name, String upstream, String checkout) {

        /**
         * Returns this as plain values, for a wire.
         *
         * @return The entry.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("upstream", upstream);
            map.put("checkout", checkout);
            return map;
        }
    }

    /** Why a repository could not be added or removed. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message What to tell the person.
         */
        public Refused(final String message) {
            super(message);
        }
    }

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context This machine.
     */
    public DefaultProject(final SokarContext context) {
        this.context = context;
    }

    /**
     * Returns whether a name is the built-in project's.
     *
     * @param name A project name.
     * @return {@code true} for {@value #NAME}.
     */
    public static boolean is(final @Nullable String name) {
        return NAME.equals(name);
    }

    /**
     * Returns the repositories in it, in the order they were added.
     *
     * @return The entries; empty before the first.
     */
    public List<Entry> entries() {
        final Path file = record();
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            final List<Entry> entries = new ArrayList<>();
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof List<?> listed) {
                for (final Object each : listed) {
                    if (each instanceof Map<?, ?> entry && entry.get("name") instanceof String name
                            && entry.get("upstream") instanceof String upstream) {
                        entries.add(new Entry(name, upstream,
                                entry.get("checkout") instanceof String checkout ? checkout : ""));
                    }
                }
            }
            return List.copyOf(entries);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("the repositories of '" + NAME + "' cannot be read from " + file + ": "
                    + ex.getMessage(), ex);
        }
    }

    /**
     * Returns the entry whose upstream is this one, or {@code null}.
     *
     * @param upstream A repository's address.
     * @return The entry, or {@code null}.
     */
    public @Nullable Entry withUpstream(final String upstream) {
        return entries().stream().filter(entry -> RepositoryAddress.same(entry.upstream(), upstream)).findFirst()
                .orElse(null);
    }

    /**
     * Adds a repository, or returns the one already there for the same upstream.
     *
     * @param upstream Where approved work goes.
     * @param name What to call it, or {@code null} to name it after the repository.
     * @param checkout The checkout it is added from, or "".
     * @return What is in it now for that upstream.
     * @throws Refused If the name is taken by another repository or is not a repository name.
     */
    public Entry add(final String address, final @Nullable String name, final String checkout) throws Refused {
        final Refused[] refused = new Refused[1];
        final Entry added = locked(() -> {
            try {
                return addHeld(address, name, checkout);
            } catch (Refused ex) {
                refused[0] = ex;
                return null;
            }
        });
        if (refused[0] != null) {
            throw refused[0];
        }
        return java.util.Objects.requireNonNull(added);
    }

    private Entry addHeld(final String address, final @Nullable String name, final String checkout)
            throws Refused {
        // Never kept with a credential in it: what is written here is listed, printed and answered.
        final String upstream = RepositoryAddress.withoutCredential(address);
        if (upstream.isBlank()) {
            throw new Refused("a repository in '" + NAME + "' needs an address its approved work goes to");
        }
        final Entry existing = withUpstream(upstream);
        if (existing != null) {
            return existing;
        }
        final List<Entry> entries = new ArrayList<>(entries());
        final String chosen = name != null && !name.isBlank() ? name : unused(RepositoryAddress.nameOf(upstream),
                entries);
        if (!chosen.matches(Repository.NAME_PATTERN) || chosen.equals(NAME)) {
            throw new Refused("'" + chosen + "' is not a repository name: lowercase letters, digits and dashes,"
                    + " and not '" + NAME + "'");
        }
        if (entries.stream().anyMatch(entry -> entry.name().equals(chosen))) {
            throw new Refused("'" + NAME + "' has a repository called '" + chosen + "' already; name this one"
                    + " differently");
        }
        final Entry added = new Entry(chosen, upstream.strip(), checkout);
        entries.add(added);
        write(entries);
        return added;
    }

    /**
     * Takes a repository out. The mirror of its work stays, so nothing reviewed or waiting is lost by it; this machine's
     * deploy key for it goes, and is answered, so whoever registered it at the forge removes it there.
     *
     * @param name The repository.
     * @return The deploy keys forgotten, or {@code null} when it was not there.
     */
    public @Nullable List<DeployKeys.Key> remove(final String name) {
        return locked(() -> removeHeld(name));
    }

    private @Nullable List<DeployKeys.Key> removeHeld(final String name) {
        final List<Entry> entries = new ArrayList<>(entries());
        if (entries.stream().noneMatch(entry -> entry.name().equals(name))) {
            return null;
        }
        // While the repository is still in the file the key is found by.
        final List<DeployKeys.Key> forgotten = DeployKeys.forget(context,
                org.fuin.sokar.core.project.ProjectReader.read(file()), name);
        entries.removeIf(entry -> entry.name().equals(name));
        write(entries);
        return forgotten;
    }

    /**
     * Writes the project file the rest of Sokar reads, from Sokar's settings and the repositories in it, and returns
     * where it is. Written anew each time, so an edit to it never lasts.
     *
     * @return The file.
     */
    public Path file() {
        return java.util.Objects.requireNonNull(locked(this::fileHeld));
    }

    private Path fileHeld() {
        final StringBuilder text = new StringBuilder();
        text.append("# Written by Sokar from its own settings and the repositories added here. Not to be edited:\n")
                .append("# it is written anew whenever it is read. A project with settings of its own is a\n")
                .append("# project repository that this machine follows.\n")
                .append("project:\n")
                .append("  name: \"").append(NAME).append("\"\n")
                .append("  description: \"Work on a repository no followed project names\"\n")
                .append("  security_class: \"guarded\"\n")
                .append("image:\n")
                .append("  base_image: \"").append(BASE_IMAGE).append("\"\n");
        final List<Entry> entries = entries();
        if (!entries.isEmpty()) {
            text.append("repositories:\n");
            for (final Entry entry : entries) {
                text.append("  ").append(entry.name()).append(":\n")
                        .append("    upstream: ").append(Json.write(entry.upstream())).append('\n');
            }
        }
        final Path file = record().resolveSibling("project.yml");
        try {
            Files.createDirectories(file.getParent());
            final Path staged = Files.createTempFile(file.getParent(), ".project.yml.", ".new");
            Files.writeString(staged, text.toString(), StandardCharsets.UTF_8);
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException("the project file of '" + NAME + "' cannot be written: " + ex.getMessage(),
                    ex);
        }
        return file;
    }

    private Path record() {
        return context.paths().xdg().state().resolve(NAME).resolve("repositories.json");
    }

    private void write(final List<Entry> entries) {
        final Path file = record();
        try {
            Files.createDirectories(file.getParent());
            final Path staged = Files.createTempFile(file.getParent(), ".repositories.json.", ".new");
            Files.writeString(staged, Json.write(entries.stream().map(Entry::asMap).toList()),
                    StandardCharsets.UTF_8);
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException("the repositories of '" + NAME + "' cannot be written: " + ex.getMessage(),
                    ex);
        }
    }

    private static String unused(final String wanted, final List<Entry> entries) {
        String candidate = wanted;
        for (int number = 2; candidate.equals(NAME) || taken(candidate, entries); number++) {
            candidate = wanted + "-" + number;
        }
        return candidate;
    }

    private static boolean taken(final String name, final List<Entry> entries) {
        return entries.stream().anyMatch(entry -> entry.name().equals(name));
    }

    /**
     * Runs a read and write of this project's record under one lock, across processes.
     * <p>
     * The daemon and a command write it alike; through one staged name and without a lock, the second writer found its
     * file moved away and a repository could be lost between a read and a write.
     */
    private <T> @Nullable T locked(final java.util.function.Supplier<@Nullable T> body) {
        try {
            return FileLocks.holding(record().resolveSibling(".default.lock"), body::get);
        } catch (IOException ex) {
            throw new IllegalStateException("the record of '" + NAME + "' cannot be locked: " + ex.getMessage(), ex);
        }
    }
}
