package org.fuin.sokar.core.project;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a {@code project.yml} into a {@link Project}.
 * <p>
 * The YAML is loaded as plain maps with {@link SafeConstructor} and mapped by hand. Binding
 * straight onto the record would need reflection metadata in a native image and would let the file
 * name any class on the classpath; neither is worth the few lines it saves.
 * <p>
 * This reader never writes. Editing a project file is a separate, patch-only operation, so that
 * comments and formatting survive - see constraint C4.
 */
public final class ProjectReader {

    private ProjectReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a project definition from a file.
     *
     * @param file Path to {@code project.yml}.
     * @return The project.
     * @throws ProjectException If the file cannot be read or does not describe a usable project.
     */
    public static Project read(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ProjectException("No project file at " + file
                    + ". Run 'sokar task run' in a terminal and it will offer to write one.");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // Absolute, because the message is read somewhere else than it was typed: "project.yml
            // has no 'project' section" leaves a reader guessing which project.yml, and a person
            // driving this over a socket or from a script has no working directory in front of
            // them at all.
            return read(reader, file.toAbsolutePath().toString());
        } catch (IOException ex) {
            throw new ProjectException("Cannot read " + file, ex);
        }
    }

    /**
     * Reads a project definition from a reader.
     *
     * @param reader Source of the YAML.
     * @param origin Name used in error messages.
     * @return The project.
     * @throws ProjectException If the content does not describe a usable project.
     */
    public static Project read(Reader reader, String origin) {

        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);

        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            throw new ProjectException("Cannot parse " + origin + ": " + ex.getMessage(), ex);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new ProjectException(origin + " is empty or is not a YAML mapping");
        }

        final Map<?, ?> project = section(root, "project", origin);
        final Map<?, ?> image = section(root, "image", origin);

        return new Project(
                required(project, "name", origin, "project"),
                text(project.get("description")),
                SecurityClass.parse(required(project, "security_class", origin, "project")),
                required(image, "base_image", origin, "image"),
                snippet(image, origin),
                text(project.get("upstream")).isEmpty() ? null : text(project.get("upstream")),
                limits(root, origin),
                egress(root, origin),
                packageSources(image, origin),
                mail(root, origin),
                // Absent means no. A setting that says unread work may leave is one somebody has
                // to write down, because forgetting it must never be the permissive answer.
                Boolean.TRUE.equals(project.get("unread_work_may_leave")),
                repositories(root, origin));
    }

    /**
     * Reads the optional {@code repositories} section, which names the work repositories.
     * <p>
     * Absent means the project has only its own, which is what a project still being planned looks
     * like. A mapping rather than a list because the name is the key a person types at
     * {@code sokar task start}, and a list of mappings each carrying its own {@code name} would
     * let two entries claim the same one without YAML noticing.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @return What the project declared, empty when it declared nothing.
     */
    private static java.util.List<Repository> repositories(Map<?, ?> root, String origin) {
        final Object value = root.get("repositories");
        if (value == null) {
            return java.util.List.of();
        }
        if (!(value instanceof Map<?, ?> declared)) {
            throw new ProjectException(origin
                    + ": 'repositories' must be a mapping of name to repository");
        }
        final java.util.List<Repository> read = new java.util.ArrayList<>();
        for (final Map.Entry<?, ?> entry : declared.entrySet()) {
            final String name = String.valueOf(entry.getKey());
            if (entry.getValue() == null) {
                // A name with nothing under it is a repository with no upstream, which is a
                // repository whose work stays here. Legitimate, so it is read rather than refused.
                read.add(new Repository(name, null));
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> repository)) {
                throw new ProjectException(origin + ": 'repositories." + name
                        + "' must be a mapping with 'upstream'");
            }
            read.add(new Repository(name,
                    text(repository.get("upstream")).isEmpty() ? null
                            : text(repository.get("upstream")),
                    text(repository.get("description")),
                    egress(repository, origin, "repositories." + name + "."),
                    declaredLimits(repository, origin, name)));
        }
        return java.util.List.copyOf(read);
    }

    /**
     * Reads the optional {@code image.package_sources} list.
     * <p>
     * Where apt fetches from while the image is built. A project that names none gets
     * {@link Project#DEFAULT_PACKAGE_SOURCES} at the point of use; {@code null} here means the
     * project named none, which is not the same as naming the default.
     *
     * @param image The image section.
     * @param origin Name used in error messages.
     * @return What the project declared, or the default.
     */
    private static java.util.@org.jspecify.annotations.Nullable List<String> packageSources(
            Map<?, ?> image, String origin) {
        final Object value = image.get("package_sources");
        if (value == null) {
            return null;
        }
        if (!(value instanceof java.util.List<?> list)) {
            throw new ProjectException(origin + ": 'image.package_sources' is a list of URLs");
        }
        return list.stream().map(ProjectReader::text).toList();
    }

    /**
     * Reads a peer's optional {@code per_day}.
     *
     * @param peer The peer's mapping.
     * @param name Its name, for error messages.
     * @param origin Name used in error messages.
     * @return What it declared, or the default.
     */
    private static int perDay(Map<?, ?> peer, String name, String origin) {
        final Object value = peer.get("per_day");
        if (value == null) {
            return Mail.Peer.DEFAULT_PER_DAY;
        }
        if (!(value instanceof Number number)) {
            throw new ProjectException(origin + ": 'mail.peers." + name
                    + ".per_day' is a number of messages a day");
        }
        return number.intValue();
    }

    /**
     * Reads the optional {@code mail} section, which names the peers a task may address.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @return What the project declared, empty when it declared nothing.
     */
    private static Mail mail(Map<?, ?> root, String origin) {
        final Object value = root.get("mail");
        if (value == null) {
            return Mail.none();
        }
        if (!(value instanceof Map<?, ?> mail)) {
            throw new ProjectException(origin + ": 'mail' must be a mapping");
        }
        final Object peers = mail.get("peers");
        if (peers == null) {
            return Mail.none();
        }
        if (!(peers instanceof Map<?, ?> declared)) {
            throw new ProjectException(origin + ": 'mail.peers' must be a mapping of name to peer");
        }
        final java.util.List<Mail.Peer> read = new java.util.ArrayList<>();
        for (final Map.Entry<?, ?> entry : declared.entrySet()) {
            final String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> peer)) {
                throw new ProjectException(origin + ": 'mail.peers." + name
                        + "' must be a mapping with 'address' and 'trust'");
            }
            read.add(new Mail.Peer(name, text(peer.get("address")),
                    // Vouched is the narrower promise - it skips the check on the way in - so an
                    // omitted trust level is the wider one rather than the convenient one.
                    text(peer.get("trust")).isEmpty() ? Mail.Peer.EXTERNAL
                            : text(peer.get("trust")),
                    perDay(peer, name, origin)));
        }
        return new Mail(java.util.List.copyOf(read));
    }

    /**
     * Reads the optional {@code egress} section.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @return What the project declared, empty when it declared nothing.
     */
    private static Egress egress(Map<?, ?> root, String origin) {
        return egress(root, origin, "");
    }

    /**
     * Reads an {@code egress} block, at the top level or under a repository.
     * <p>
     * The same reader for both, so the two cannot come to mean different things - and so that a
     * bad set name under a repository is refused with the same message and the same rule as one at
     * the top.
     *
     * @param root The mapping the block sits in.
     * @param origin Name used in error messages.
     * @param path What to put before the key in an error message, so a reader is told which
     *        {@code egress} block was wrong.
     * @return What was declared, empty when nothing was.
     */
    private static Egress egress(Map<?, ?> root, String origin, String path) {
        final Object value = root.get("egress");
        if (value == null) {
            return Egress.none();
        }
        if (!(value instanceof Map<?, ?> egress)) {
            throw new ProjectException(origin + ": '" + path + "egress' must be a mapping");
        }
        return new Egress(strings(egress.get("sets"), path + "egress.sets", origin),
                strings(egress.get("domains"), path + "egress.domains", origin));
    }

    /**
     * Reads a repository's optional {@code limits} block.
     * <p>
     * <strong>Absent keys stay absent.</strong> Unlike the project's limits, which fall back to
     * {@link Limits#defaults()} key by key, a key nobody wrote here has to remain unwritten - it
     * means "the project's", and resolving it to a default at this point would quietly undo a
     * project that had deliberately raised one.
     *
     * @param repository The repository's mapping.
     * @param origin Name used in error messages.
     * @param name The repository's name, for error messages.
     * @return What it declared, all absent when it declared nothing.
     */
    private static Limits.Declared declaredLimits(Map<?, ?> repository, String origin,
            String name) {
        final Object value = repository.get("limits");
        if (value == null) {
            return Limits.Declared.none();
        }
        if (!(value instanceof Map<?, ?> limits)) {
            throw new ProjectException(origin + ": 'repositories." + name
                    + ".limits' must be a mapping");
        }
        final Object pids = limits.get("pids");
        if (pids != null && !(pids instanceof Number)) {
            throw new ProjectException(origin + ": 'repositories." + name
                    + ".limits.pids' must be a number, not '" + pids + "'");
        }
        return new Limits.Declared(
                limits.get("memory") == null ? null : text(limits.get("memory")),
                limits.get("cpus") == null ? null : text(limits.get("cpus")),
                pids == null ? null : ((Number) pids).intValue());
    }

    /**
     * Reads a list of strings, refusing anything that is not one.
     * <p>
     * A scalar is refused rather than wrapped: {@code sets: maven} is a plausible typo for
     * {@code sets: [maven]}, and accepting both would make the file's meaning depend on a detail
     * of YAML rather than on what it says.
     *
     * @param value Raw value, may be {@code null}.
     * @param key Key path used in error messages.
     * @param origin Name used in error messages.
     * @return The entries, empty when the key is absent.
     */
    private static java.util.List<String> strings(Object value, String key, String origin) {
        if (value == null) {
            return java.util.List.of();
        }
        if (!(value instanceof java.util.List<?> list)) {
            throw new ProjectException(origin + ": '" + key + "' must be a list");
        }
        final java.util.List<String> found = new java.util.ArrayList<>();
        for (final Object entry : list) {
            if (!(entry instanceof String text) || text.isBlank()) {
                throw new ProjectException(origin + ": '" + key + "' has an entry that is not a"
                        + " name: " + entry);
            }
            found.add(text.strip());
        }
        return found;
    }

    private static Map<?, ?> section(Map<?, ?> root, String name, String origin) {
        final Object value = root.get(name);
        if (value == null) {
            throw new ProjectException(origin + " has no '" + name + "' section");
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new ProjectException(origin + ": '" + name + "' must be a mapping");
        }
        return map;
    }

    /**
     * Reads the optional {@code limits} section.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @return Declared limits, falling back to the defaults key by key.
     */
    private static Limits limits(Map<?, ?> root, String origin) {
        if (!(root.get("limits") instanceof Map<?, ?> limits)) {
            return Limits.defaults();
        }
        final String memory = text(limits.get("memory"));
        final String cpus = text(limits.get("cpus"));
        final Object pids = limits.get("pids");
        try {
            return new Limits(
                    // 'none' is how a project opts out on purpose, which reads differently from
                    // having forgotten to set one.
                    memory.isEmpty() ? Limits.DEFAULT_MEMORY : "none".equals(memory) ? null : memory,
                    cpus.isEmpty() || "none".equals(cpus) ? null : cpus,
                    pids == null ? Limits.DEFAULT_PIDS : Integer.parseInt(String.valueOf(pids)));
        } catch (NumberFormatException ex) {
            throw new ProjectException(origin + ": 'limits.pids' must be a number, not '"
                    + pids + "'");
        }
    }

    private static String required(Map<?, ?> section, String key, String origin, String sectionName) {
        final Object value = section.get(key);
        if (value == null) {
            throw new ProjectException(origin + ": '" + sectionName + "." + key + "' is required");
        }
        return String.valueOf(value);
    }

    /**
     * Reads the operator's own image lines, either inline or from a file beside the project.
     * <p>
     * Both forms exist because both are wanted: a couple of packages read better inline, and a
     * long snippet reads better in a file its own syntax highlighting understands.
     */
    @Nullable
    private static String snippet(Map<?, ?> image, String origin) {
        final Object inline = image.get("snippet");
        final Object file = image.get("snippet_file");
        if (inline != null && file != null) {
            throw new ProjectException(origin
                    + ": 'image.snippet' and 'image.snippet_file' are mutually exclusive");
        }
        if (inline != null) {
            return String.valueOf(inline);
        }
        if (file == null) {
            return null;
        }
        final Path path = Path.of(origin).toAbsolutePath().getParent().resolve(String.valueOf(file));
        if (!Files.isRegularFile(path)) {
            throw new ProjectException(origin + ": no image snippet at " + path);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ProjectException("Cannot read " + path, ex);
        }
    }

    private static String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
