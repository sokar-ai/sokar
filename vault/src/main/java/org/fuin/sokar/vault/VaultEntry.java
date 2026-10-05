package org.fuin.sokar.vault;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * One credential, what kind of credential it is, and the settings that go with it.
 * <p>
 * <strong>One secret, and configuration beside it.</strong> A credential stopped being one value: an OAuth
 * client is a client id, a token URL and scopes as well as its secret. Only the secret is {@link #value};
 * the rest are {@link #settings}, which are configuration - usable, printable and listed - and never
 * judged as though they were a pasted key.
 * <p>
 * The kind belongs to the secret, not to the run that uses it: a provider that accepts two kinds
 * puts them in different headers, and sending one as the other fails looking exactly like a wrong
 * key. Stored once here rather than remembered on every command.
 *
 * @param value The secret.
 * @param type Kind of credential, or {@code null} when the entry predates kinds.
 */
public record VaultEntry(String value, @Nullable String type, Map<String, String> settings) {

    /** What a setting's name may be: lower case, digits, '_' and '-', starting with a letter. */
    private static final java.util.regex.Pattern SETTING = java.util.regex.Pattern.compile("[a-z][a-z0-9_-]*");

    /**
     * Constructor.
     *
     * @param value The secret.
     * @param type Kind of credential, or {@code null} when the entry predates kinds.
     * @param settings What goes with it that is not secret, by name.
     */
    public VaultEntry {
        for (final String name : settings.keySet()) {
            if (!SETTING.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' is not a setting name: lower case, digits,"
                        + " '_' and '-', starting with a letter");
            }
        }
        settings = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(settings));
    }

    /**
     * Constructor for a credential that is its secret alone, which every one was before settings existed.
     *
     * @param value The secret.
     * @param type Kind of credential, or {@code null}.
     */
    public VaultEntry(String value, @Nullable String type) {
        this(value, type, Map.of());
    }

    /**
     * Says what the entry is, never its secret: a record would print every component, and whatever printed one
     * would print the credential.
     *
     * @return The kind, the secret's length and the settings.
     */
    @Override
    public String toString() {
        return "VaultEntry[type=" + type + ", value=<" + value.length() + " characters>, settings=" + settings + "]";
    }

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
