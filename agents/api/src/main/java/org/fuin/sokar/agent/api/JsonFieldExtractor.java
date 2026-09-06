package org.fuin.sokar.agent.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.sokar.wire.Json;

/**
 * Reads a named field out of a JSON config file, following a dotted path.
 * <p>
 * The second of the two shapes common enough to belong in the agent API. A dotted path rather
 * than a
 * full query language: every real case in the reference implementation is one or two levels deep,
 * and a query language here would be a small programming language embedded in YAML.
 */
public class JsonFieldExtractor implements CredentialExtractor {

    private final String fileName;

    private final List<String> path;

    private final String credentialType;

    /**
     * Constructor.
     *
     * @param fileName File to read, relative to the config directory.
     * @param dottedPath Path to the field, for example {@code auth.token}.
     * @param credentialType Type to label the result with.
     */
    public JsonFieldExtractor(String fileName, String dottedPath, String credentialType) {
        this.fileName = fileName;
        this.path = List.of(dottedPath.split("\\."));
        this.credentialType = credentialType;
    }

    @Override
    public Optional<Credential> extract(Path configDirectory) {

        final Path file = configDirectory.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Object current = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            for (final String segment : path) {
                if (!(current instanceof Map<?, ?> map)) {
                    return Optional.empty();
                }
                current = map.get(segment);
            }
            return current instanceof String value && !value.isBlank()
                    ? Optional.of(Credential.of(credentialType, value))
                    : Optional.empty();
        } catch (IOException ex) {
            throw new AgentException("Cannot read " + file, ex);
        } catch (RuntimeException ex) {
            throw new AgentException("Cannot parse " + file + ": " + ex.getMessage(), ex);
        }
    }
}
