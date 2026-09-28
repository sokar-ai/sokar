package org.fuin.sokar.hooks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.fuin.sokar.wire.Json;
import org.fuin.sokar.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * The container state an OCI runtime writes to a hook's standard input.
 * <p>
 * Defined by the OCI runtime specification: the runtime pipes one JSON object in and expects the
 * hook to exit. Only the fields Sokar needs are read; an unknown field is ignored, because the
 * runtime is free to add them.
 *
 * @param id Container id.
 * @param pid Process id of the container's init process, in the host's pid namespace.
 * @param annotations Annotations the container was created with.
 */
public record OciState(String id, long pid, Map<String, String> annotations) {

    /**
     * Reads the state from a stream.
     *
     * @param in Stream the runtime writes to.
     * @return Parsed state.
     * @throws IOException If the stream cannot be read.
     */
    public static OciState read(InputStream in) throws IOException {
        return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    /**
     * Parses the state.
     *
     * @param json The document the runtime wrote.
     * @return Parsed state.
     */
    public static OciState parse(String json) {

        if (!(Json.parse(json) instanceof Map<?, ?> root)) {
            throw new JsonException("The OCI state must be a JSON object");
        }

        final Object id = root.get("id");
        if (!(id instanceof String containerId)) {
            throw new JsonException("The OCI state has no 'id'");
        }

        // 'pid' is absent at poststop, where the process is already gone. That is not an error.
        final long pid = root.get("pid") instanceof Number number ? number.longValue() : 0L;

        final Map<String, String> annotations = new java.util.LinkedHashMap<>();
        if (root.get("annotations") instanceof Map<?, ?> map) {
            map.forEach((key, value) -> annotations.put(String.valueOf(key), String.valueOf(value)));
        }

        return new OciState(containerId, pid, Map.copyOf(annotations));
    }

    /**
     * Returns the value of an annotation.
     *
     * @param key Annotation key.
     * @return Value, or {@code null}.
     */
    public @Nullable String annotation(String key) {
        return annotations.get(key);
    }
}
