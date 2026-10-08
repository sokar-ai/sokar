package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.jspecify.annotations.Nullable;

/**
 * Where Sokar keeps the files of the vault and the credentials declared on this machine.
 * <p>
 * One class per area, beside {@link SokarPaths}, which keeps only the roots and what belongs to the machine as a
 * whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record VaultPaths(SokarPaths paths) {

    /**
     * Returns the vault this process should use.
     * <p>
     * Overridable because an acceptance run must not read or write the operator's own vault: the
     * data directory itself cannot be redirected, since the container runtime keeps its whole
     * image store under it.
     *
     * @return Path of the vault file.
     */
    public Path vaultFile() {
        final String override = System.getenv(SokarPaths.VAULT_VARIABLE);
        return override == null || override.isBlank()
                ? paths.xdg().data().resolve("vault.bin") : Path.of(override);
    }

    /**
     * Returns the keyring description under which this vault's passphrase is cached.
     * <p>
     * Keyed by the vault it unlocks. A single shared name meant unlocking one vault silently
     * replaced the cached passphrase of another, which is how an acceptance run could log the
     * operator out of their own.
     *
     * @return Keyring description.
     */
    public String vaultKeyringKey() {
        final Path file = vaultFile();
        return "sokar:vault:" + Integer.toHexString(file.toAbsolutePath().toString().hashCode());
    }

    /**
     * Returns the keyring description under which a device's share is cached.
     * <p>
     * Its own entry, never the passphrase's. They are different secrets with different lifetimes -
     * revoking a device must not disturb a cached passphrase, and forgetting the passphrase must
     * not silently keep a device's way in - and one name for both would make each of those a
     * surprise.
     *
     * @return Keyring description.
     */
    public String vaultShareKeyringKey() {
        return vaultKeyringKey() + ":share";
    }

    /**
     * Returns where this machine records what it connects out with.
     * <p>
     * <strong>Beside the vault, not inside it.</strong> It holds no secret - kinds, destinations,
     * usernames, and where each value lives - and being readable with the vault shut is the point:
     * only then can a machine tell <em>"a credential for this host is configured, unlock the
     * vault"</em> from <em>"nothing is configured, store one"</em>, and those two send a person to
     * opposite places.
     * <p>
     * In the configuration directory because it is a decision, like the pinned signers beside it.
     *
     * @return The file, which need not exist. A machine with none falls back to the names a git
     *         URL implies.
     */
    public Path credentialRegistry() {
        return paths.xdg().config().resolve("credentials.yml");
    }
}
