package org.fuin.sokar.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The host-side endpoints one task container is wired to.
 * <p>
 * Grouped rather than passed as four more arguments, because they share a property that is easy
 * to lose track of: each is decided <em>before</em> the container is created, since the firewall
 * ruleset has to name the gate's port and the socket has to exist before it can be mounted.
 *
 * @param gateAddress Address the git gate listens on, or {@code null} when the task has no
 *        workspace.
 * @param gatePort Port the git gate listens on, or zero.
 * @param vaultSocket Host path of the credential proxy's socket, or {@code null} when the task
 *        brokers no credential.
 */
public record TaskWiring(@Nullable String gateAddress, int gatePort, @Nullable Path vaultSocket) {

    /** Where the vault socket is mounted inside the container. */
    public static final String VAULT_MOUNT = "/run/sokar/vault.sock";

    /**
     * Base URL handed to an agent alongside {@link #VAULT_MOUNT}.
     * <p>
     * The socket carries the traffic, so the host and port here are never dialled - but the
     * variable still has to be set. Measured: with only the socket variable set, Claude Code
     * falls back to its own compiled-in endpoint and resolves {@code api.anthropic.com} 184 times
     * in a single run, which the firewall then drops. Setting a base URL is what stops it.
     * <p>
     * Loopback rather than a real name so that an agent which ignores the socket variable fails
     * closed against nothing listening, instead of reaching the provider.
     */
    public static final String VAULT_URL = "http://localhost:9419";

    /**
     * Returns wiring for a task with nothing attached.
     *
     * @return Empty wiring.
     */
    public static TaskWiring none() {
        return new TaskWiring(null, 0, null);
    }

    /**
     * Returns a copy with the vault socket set.
     *
     * @param socket Host path of the socket.
     * @return New wiring.
     */
    public TaskWiring withVaultSocket(Path socket) {
        return new TaskWiring(gateAddress, gatePort, socket);
    }
}
