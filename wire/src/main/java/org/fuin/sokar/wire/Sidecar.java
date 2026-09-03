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
 * @param version Schema version, so a newer hook can recognise an older file.
 * @param project Project name, for log messages and for the operator reading the file.
 * @param securityClass The project's security class, lower case.
 * @param rulesetFile Path to the generated nftables ruleset the nft hook loads.
 * @param dnsConfigFile Path to the generated dnsmasq configuration the supervisor hook loads.
 * @param stateDirectory Directory the hooks write their own state into.
 */
public record Sidecar(int version, String project, String securityClass,
        String rulesetFile, String dnsConfigFile, String stateDirectory) {

    /**
     * Current schema version.
     * <p>
     * Bumped to 2 when the resolver configuration was added. The hooks are installed from the
     * same package as the {@code sokar} binary, so the two are never out of step in a normal
     * installation - and a hook that quietly ignored this field would leave the container with a
     * resolver address and nothing listening on it, which is worse than refusing to start.
     */
    public static final int VERSION = 2;

    /** OCI annotation the hooks are gated on, and whose value is the path to this file. */
    public static final String ANNOTATION = "org.fuin.sokar.sidecar";

    /**
     * Constructor with all data.
     *
     * @param version Schema version.
     * @param project Project name.
     * @param securityClass The project's security class, lower case.
     * @param rulesetFile Path to the generated nftables ruleset.
     * @param dnsConfigFile Path to the generated dnsmasq configuration.
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
                string(map, "stateDirectory"));
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
