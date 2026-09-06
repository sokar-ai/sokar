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
            return read(reader, file.toString());
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
                limits(root, origin));
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
