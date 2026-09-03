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
            throw new ProjectException("No project file at " + file);
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
                required(image, "base_image", origin, "image"));
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

    private static String required(Map<?, ?> section, String key, String origin, String sectionName) {
        final Object value = section.get(key);
        if (value == null) {
            throw new ProjectException(origin + ": '" + sectionName + "." + key + "' is required");
        }
        return String.valueOf(value);
    }

    private static String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
