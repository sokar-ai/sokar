package org.fuin.sokar.vault;

import org.jspecify.annotations.Nullable;

/**
 * One credential and what kind of credential it is.
 * <p>
 * The kind belongs to the secret, not to the run that uses it: a provider that accepts two kinds
 * puts them in different headers, and sending one as the other fails looking exactly like a wrong
 * key. Stored once here rather than remembered on every command.
 *
 * @param value The secret.
 * @param type Kind of credential, or {@code null} when the entry predates kinds.
 */
public record VaultEntry(String value, @Nullable String type) {

    /**
     * Returns an entry of unstated kind.
     *
     * @param value The secret.
     * @return Entry.
     */
    public static VaultEntry of(String value) {
        return new VaultEntry(value, null);
    }

    /**
     * Returns whether the value is too short to be a real credential.
     *
     * @return {@code true} when it is implausibly short.
     */
    public boolean implausiblyShort() {
        return value.length() < 20;
    }
}
