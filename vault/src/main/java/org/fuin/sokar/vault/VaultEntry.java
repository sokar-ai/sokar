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
     * Returns why this value does not look like a credential.
     *
     * @return A reason, or {@code null} when nothing is obviously wrong.
     */
    @Nullable
    public String suspicious() {
        if (value.startsWith("<") && value.endsWith(">")) {
            return "it looks like a placeholder from the documentation, not a credential";
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            // Every credential kind in use is a single opaque token; whitespace means a copied
            // sentence, a shell that split an argument, or a placeholder.
            return "it contains spaces, and no credential does";
        }
        if (value.length() < 20) {
            return "it is only " + value.length() + " characters, shorter than any real credential";
        }
        return null;
    }
}
