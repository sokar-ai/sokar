package org.fuin.sokar.core.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One key a project file may hold, described for a person who has not written one before.
 * <p>
 * The words are Sokar's, kept beside the keys they describe, so an editor built from them never says something of
 * its own about what a key does.
 *
 * @param section Where it sits: {@code ""} for the top level, {@code mail.peers.<peer>} and
 *        {@code repositories.<repository>} for each named one.
 * @param name The key.
 * @param describe What it means, in one sentence.
 * @param type {@code string}, {@code boolean}, {@code integer}, {@code list} or {@code map}.
 * @param values The values it takes, when they are a fixed set; empty otherwise.
 * @param defaultValue What it is when the file does not say, as the file would spell it; "" for nothing.
 * @param required Whether a project file without it is refused.
 */
public record ProjectKey(String section, String name, String describe, String type, List<String> values,
        String defaultValue, boolean required) {

    /**
     * Constructor with a defensive copy.
     *
     * @param section Where it sits.
     * @param name The key.
     * @param describe What it means.
     * @param type Its type.
     * @param values The values it takes.
     * @param defaultValue Its default.
     * @param required Whether it is required.
     */
    public ProjectKey {
        values = List.copyOf(values);
    }

    /**
     * Returns this as plain values, for a wire.
     *
     * @return The key.
     */
    public Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("section", section);
        map.put("name", name);
        map.put("describe", describe);
        map.put("type", type);
        map.put("values", values);
        map.put("default", defaultValue);
        map.put("required", required);
        return map;
    }
}
