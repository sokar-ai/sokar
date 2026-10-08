package org.fuin.sokar.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.vault.VaultEntry;

/**
 * The vault's name spaces that are not a person's credentials: a task's entries, an account's grants, what a
 * transport handed back.
 * <p>
 * Here, below every area, because the machine's context reads the vault and leaves them out, and the task's,
 * the vault's and the messaging areas each write one of them.
 */
public final class VaultNames {

    /** Where a task's own entries are. */
    public static final String TASK_PREFIX = "task/";

    /** Where an account's grants are - the refresh token a person granted once for a service. */
    public static final String GRANT_PREFIX = "grant/";

    /** Where what a transport handed back is - the account's, a project's, a task's secrets for its conversation. */
    public static final String TRANSPORT_PREFIX = "transport/";

    private VaultNames() {
    }

    /**
     * Tells whether a name is in one of these name spaces, which no person puts or removes by hand.
     *
     * @param name An entry's name.
     * @return true when it is a task's, a grant's or a transport's.
     */
    public static boolean reserved(String name) {
        return name.startsWith(TASK_PREFIX) || name.startsWith(GRANT_PREFIX) || name.startsWith(TRANSPORT_PREFIX);
    }

    /**
     * Leaves these entries out of what the vault holds, for anything that lists or chooses credentials.
     *
     * @param entries Everything the vault holds.
     * @return Only the credentials.
     */
    public static Map<String, VaultEntry> credentialsOnly(Map<String, VaultEntry> entries) {
        final Map<String, VaultEntry> credentials = new LinkedHashMap<>();
        entries.forEach((name, entry) -> {
            if (!reserved(name)) {
                credentials.put(name, entry);
            }
        });
        return credentials;
    }
}
