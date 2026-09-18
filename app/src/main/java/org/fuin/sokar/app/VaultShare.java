package org.fuin.sokar.app;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.fuin.sokar.vault.KernelKeyring;

/**
 * A device's share, held in the kernel keyring for as long as an unlock is meant to last.
 * <p>
 * <strong>The share and not the master key.</strong> Caching the unwrapped master key would create
 * a secret on this machine that outlives every revocation: a slot can be deleted, but a master key
 * somebody already holds opens the vault forever. A share is the thing the device already has, it
 * stops working the moment its keyslot is removed, and it is no more than what the passphrase cache
 * already is - a way in, held in memory, with a timeout.
 * <p>
 * <strong>The kernel keyring, not a file.</strong> B60 says the node persists neither the share nor
 * the master key, and this is how that is kept true: the keyring is memory, it is gone on reboot,
 * and the kernel enforces the timeout rather than a process remembering to.
 */
public final class VaultShare {

    /** How long an unlock lasts when nobody says. */
    public static final Duration DEFAULT = Duration.ofHours(8);

    private VaultShare() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Holds a share for a while.
     *
     * @param paths Where this machine keeps things.
     * @param share The device's share.
     * @param howLong How long the kernel should keep it.
     */
    public static void keep(final SokarPaths paths, final byte[] share, final Duration howLong) {
        final char[] encoded = Base64.getEncoder().encodeToString(share).toCharArray();
        try {
            new KernelKeyring(paths.vaultShareKeyringKey()).store(encoded, howLong);
        } finally {
            Arrays.fill(encoded, '\0');
        }
    }

    /**
     * Returns the share this machine is holding, if any.
     *
     * @param paths Where this machine keeps things.
     * @return The share, or empty when no unlock is current.
     */
    public static Optional<byte[]> held(final SokarPaths paths) {
        if (!KernelKeyring.available()) {
            return Optional.empty();
        }
        return new KernelKeyring(paths.vaultShareKeyringKey()).read().map(encoded -> {
            try {
                return Base64.getDecoder().decode(new String(encoded));
            } catch (final IllegalArgumentException ex) {
                // Something else is under that name. Not ours, so not a way in.
                return null;
            } finally {
                Arrays.fill(encoded, '\0');
            }
        });
    }

    /**
     * Forgets the share, so the vault is shut to devices at once.
     *
     * @param paths Where this machine keeps things.
     * @return What was there.
     */
    public static KernelKeyring.Forgotten forget(final SokarPaths paths) {
        if (!KernelKeyring.available()) {
            return KernelKeyring.Forgotten.NOTHING_CACHED;
        }
        return new KernelKeyring(paths.vaultShareKeyringKey()).forget();
    }
}
