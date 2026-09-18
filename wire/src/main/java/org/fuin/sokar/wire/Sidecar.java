package org.fuin.sokar.wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the launcher tells the OCI hooks about one container.
 * <p>
 * This is the whole contract between the {@code sokar} binary and the three hook binaries. They
 * share no other code and no library, so the file is the interface: the launcher writes it before
 * creating the container, and each hook reads it when the runtime fires.
 * <p>
 * Changing a field name changes that contract. The hooks are installed once and outlive any single
 * release of the CLI, so a rename must be treated as a protocol change, not a refactoring.
 *
 * @param version Schema version, so a newer hook can recognize an older file.
 * @param project Project name, for log messages and for the operator reading the file.
 * @param securityClass The project's security class, lower case.
 * @param rulesetFile Path to the generated nftables ruleset the nft hook loads.
 * @param dnsConfigFile Path to the generated dnsmasq configuration the supervisor hook loads.
 * @param sokarBinary Path to the {@code sokar} binary, so a hook can start the long-running
 *        helpers that need it. The hooks are static and make no native calls; anything that does
 *        has to be a separate process, and this is where they find it.
 * @param stateDirectory Directory the hooks write their own state into.
 */
public record Sidecar(int version, String project, String securityClass,
        String rulesetFile, String dnsConfigFile, String sokarBinary, String stateDirectory) {

    /**
     * Current schema version.
     * <p>
     * Bumped to 3 when the path to the {@code sokar} binary was added. The hooks are installed from the
     * same package as the {@code sokar} binary, so the two are never out of step in a normal
     * installation - and a hook that quietly ignored this field would leave the container with a
     * resolver address and nothing listening on it, which is worse than refusing to start.
     */
    public static final int VERSION = 3;

    /** OCI annotation the hooks are gated on, and whose value is the path to this file. */
    public static final String ANNOTATION = "org.fuin.sokar.sidecar";

    /**
     * Label carrying the project a task belongs to.
     * <p>
     * <strong>The same fact as in the sidecar, kept where it lives as long as the task does.</strong>
     * The sidecar is in {@code $XDG_RUNTIME_DIR}, which the system destroys when the user's last
     * session ends - so after a reboot every surviving container listed its project and class as
     * "-". The container itself is what the listing is about, so this is where the two facts that
     * identify it belong.
     */
    public static final String PROJECT_LABEL = "org.fuin.sokar.project";

    /** Label carrying the project's security class. See {@link #PROJECT_LABEL}. */
    public static final String CLASS_LABEL = "org.fuin.sokar.class";

    /**
     * Label carrying the repository this task works on. See {@link #PROJECT_LABEL}.
     * <p>
     * On the container rather than only in the sidecar, for the reason the other two are: a
     * listing has to say which repository a task is in after a reboot, and bringing a task back
     * has to carry it over. A container created before this existed carries no such label, which
     * reads as the project's own repository - the only one there was.
     */
    public static final String REPOSITORY_LABEL = "org.fuin.sokar.repository";

    /**
     * Constructor with all data.
     *
     * @param version Schema version.
     * @param project Project name.
     * @param securityClass The project's security class, lower case.
     * @param rulesetFile Path to the generated nftables ruleset.
     * @param dnsConfigFile Path to the generated dnsmasq configuration.
     * @param sokarBinary Path to the {@code sokar} binary.
     * @param stateDirectory Directory the hooks write their own state into.
     */
    public Sidecar {
        if (project.isBlank()) {
            throw new JsonException("The sidecar needs a project name");
        }
        if (rulesetFile.isBlank()) {
            throw new JsonException("The sidecar needs a ruleset file");
        }
    }

    /**
     * Renders this sidecar as JSON.
     *
     * @return The document.
     */
    public String toJson() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", Integer.valueOf(version));
        map.put("project", project);
        map.put("securityClass", securityClass);
        map.put("rulesetFile", rulesetFile);
        map.put("dnsConfigFile", dnsConfigFile);
        map.put("sokarBinary", sokarBinary);
        map.put("stateDirectory", stateDirectory);
        return Json.write(map);
    }

    /**
     * Reads a sidecar from JSON.
     *
     * @param json The document.
     * @return The sidecar.
     * @throws JsonException If the document is not a usable sidecar.
     */
    public static Sidecar fromJson(String json) {
        if (!(Json.parse(json) instanceof Map<?, ?> map)) {
            throw new JsonException("A sidecar must be a JSON object");
        }
        final int version = (int) number(map, "version");
        if (version != VERSION) {
            // Refusing an unknown version beats guessing at its meaning: the nft hook fails closed,
            // so a misread file is worse than no file.
            throw new JsonException("Unsupported sidecar version " + version + ", expected " + VERSION);
        }
        return new Sidecar(version, string(map, "project"), string(map, "securityClass"),
                string(map, "rulesetFile"), string(map, "dnsConfigFile"),
                string(map, "sokarBinary"), string(map, "stateDirectory"));
    }

    /**
     * Writes this sidecar to a file.
     *
     * @param file Target file.
     * @throws IOException If the file cannot be written.
     */
    public void writeTo(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
    }

    /**
     * Reads a sidecar from a file.
     *
     * @param file Source file.
     * @return The sidecar.
     * @throws IOException If the file cannot be read.
     */
    public static Sidecar readFrom(Path file) throws IOException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static String string(Map<?, ?> map, String key) {
        final Object value = map.get(key);
        if (!(value instanceof String text)) {
            throw new JsonException("Sidecar field '" + key + "' is missing or not a string");
        }
        return text;
    }

    private static double number(Map<?, ?> map, String key) {
        final Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new JsonException("Sidecar field '" + key + "' is missing or not a number");
        }
        return n.doubleValue();
    }
}
