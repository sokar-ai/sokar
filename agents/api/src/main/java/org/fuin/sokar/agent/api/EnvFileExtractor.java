package org.fuin.sokar.agent.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads {@code NAME=value} from a dotenv-style file.
 * <p>
 * One of the two shapes common enough to belong in the SPI rather than in an agent. In the
 * reference implementation this same thing appears as a registry entry
 * {@code ("vibe": (extract_api_key_env, ".env", "MISTRAL_API_KEY"))} - a parameterised function
 * held in a name-keyed map, which is data wearing a function's clothing.
 */
public class EnvFileExtractor implements CredentialExtractor {

    private final String fileName;

    private final String variable;

    private final String credentialType;

    /**
     * Constructor.
     *
     * @param fileName File to read, relative to the config directory.
     * @param variable Variable holding the secret.
     * @param credentialType Type to label the result with.
     */
    public EnvFileExtractor(String fileName, String variable, String credentialType) {
        this.fileName = fileName;
        this.variable = variable;
        this.credentialType = credentialType;
    }

    @Override
    public Optional<Credential> extract(Path configDirectory) {

        final Path file = configDirectory.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final String trimmed = line.strip();
                if (trimmed.startsWith(variable + "=")) {
                    final String value = unquote(trimmed.substring(variable.length() + 1).strip());
                    return value.isEmpty() ? Optional.empty()
                            : Optional.of(Credential.of(credentialType, value));
                }
            }
            return Optional.empty();
        } catch (IOException ex) {
            throw new AgentException("Cannot read " + file, ex);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
