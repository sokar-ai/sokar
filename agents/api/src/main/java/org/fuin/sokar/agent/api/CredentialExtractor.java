package org.fuin.sokar.agent.api;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads an agent's credential out of the config directory it wrote during login.
 * <p>
 * Every vendor stores it differently, which is why this is an interface rather than a field. Two
 * shapes cover most of them and are implemented here; anything else is implemented by the agent
 * module that needs it, next to the agent it belongs to.
 */
@FunctionalInterface
public interface CredentialExtractor {

    /**
     * Reads the credential.
     *
     * @param configDirectory Directory the agent wrote its config into.
     * @return The credential, or empty if the agent has not been logged in.
     * @throws AgentException If the config exists but cannot be understood.
     */
    Optional<Credential> extract(Path configDirectory);

    /**
     * An extractor that finds nothing, for agents that need no credential at all.
     *
     * @return Extractor returning empty.
     */
    static CredentialExtractor none() {
        return directory -> Optional.empty();
    }
}
