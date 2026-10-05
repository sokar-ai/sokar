package org.fuin.sokar.vault;

import java.util.Optional;

/**
 * Somewhere the vault passphrase can come from.
 * <p>
 * Tiers are tried in order and the first that produces one wins, so a host with a password manager
 * never prompts and a host without one still works. A tier that is not configured returns empty
 * rather than throwing: not being set up is the normal case for most of them.
 */
@FunctionalInterface
public interface PassphraseSource {

    /**
     * Returns the passphrase, if this source has one.
     *
     * @return The passphrase, or empty if this source is not available or not configured.
     */
    Optional<char[]> passphrase();

    /**
     * Returns the name of this source, for messages.
     *
     * @return Short name.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
