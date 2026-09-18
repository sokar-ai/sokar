package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.sokar.wire.Json;

/**
 * The little that the host reads out of a message it is only carrying.
 * <p>
 * Deliberately almost nothing: the identifier, because a message that arrives twice has to be
 * recognised, and nothing else. What a message says is the filter's business and the reading agent's
 * business, not the carrier's.
 */
public final class MessageFile {

    private MessageFile() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns who wrote a message.
     *
     * @param message The file.
     * @return {@code ROLE_USER} when a person wrote it, {@code ROLE_AGENT} when a task did, or an
     *         empty string when the file cannot be read as a message.
     * @throws IOException Reading failed.
     */
    public static String role(final Path message) throws IOException {
        if (!Files.isRegularFile(message)) {
            return "";
        }
        final Object parsed = Json.parse(Files.readString(message, StandardCharsets.UTF_8));
        if (parsed instanceof Map<?, ?> document && document.get("role") instanceof String role) {
            return role;
        }
        return "";
    }

    /**
     * Returns a message's identifier.
     *
     * @param message The file.
     * @return The {@code messageId}, or an empty string when the file cannot be read as a message.
     *         An unreadable file is not an error here: it is held elsewhere, and this step only
     *         says that it has no id to go by.
     * @throws IOException Reading failed.
     */
    public static String id(final Path message) throws IOException {
        final Object parsed = Json.parse(Files.readString(message, StandardCharsets.UTF_8));
        if (parsed instanceof Map<?, ?> document && document.get("messageId") instanceof String id) {
            return id;
        }
        return "";
    }
}
