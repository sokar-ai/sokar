package org.fuin.sokar.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultException;
import org.fuin.sokar.vault.VaultFile;
import org.jspecify.annotations.Nullable;

/**
 * A task's own two tokens, kept in the vault so the task survives a reboot.
 * <p>
 * <strong>Why they have to survive.</strong> A container's environment is fixed when it is created, and it
 * holds both: the gate token its pushes authenticate with, and the phantom provider token its credential proxy
 * swaps for the real key. A task brought back after a reboot has to be given the same two, or every push and
 * every model call fails as a bad credential. They used to live only in the runtime directory, which a reboot
 * wipes.
 * <p>
 * <strong>In the vault, not beside it</strong> - the operator's decision: no push or provider credential lies on
 * disk outside the vault, at the cost that the first start after a reboot needs the vault unlocked, which the
 * credential proxy needs anyway.
 * <p>
 * <strong>Hidden, in a namespace of their own.</strong> They are not credentials a person put there, so every
 * listing and every choice of credential leaves out names starting with {@link #PREFIX}, and a person cannot
 * put or remove one by hand. They go when the task is removed; entries of a task that no longer exists are
 * pruned whenever they are next written.
 */
public final class TaskSecrets {

    /** Where a task's own entries are, in the vault's name space. */
    public static final String PREFIX = "task/";

    /** The gate token's entry, after the task's container name. */
    static final String GATE = "/gate-token";

    /** The phantom provider token's entry, after the task's container name. */
    static final String PROVIDER = "/provider-token";

    /**
     * What a task keeps: either token may be absent - a task without a gate, or without a provider.
     *
     * @param gate The gate token, or {@code null}.
     * @param provider The phantom provider token, or {@code null}.
     */
    public record Tokens(@Nullable String gate, @Nullable String provider) {
    }

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context Where the vault comes from.
     */
    public TaskSecrets(SokarContext context) {
        this.context = context;
    }

    /**
     * Whether a vault entry's name is a task's own rather than a credential.
     *
     * @param name The entry's name.
     * @return true for {@link #PREFIX} names
     */
    public static boolean reserved(String name) {
        return name.startsWith(PREFIX);
    }

    /**
     * Leaves a task's own entries out of what the vault holds, for anything that lists or chooses credentials.
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

    /**
     * Keeps a task's tokens, and prunes the entries of tasks that no longer exist.
     *
     * @param container The task's container.
     * @param tokens What to keep.
     * @param existing The containers that exist, including this one.
     * @return "" when they are kept, otherwise why they could not be - the task runs, and cannot come back
     *         after a reboot.
     */
    public String keep(String container, Tokens tokens, Set<String> existing) {
        if (tokens.gate() == null && tokens.provider() == null) {
            return "";
        }
        final Optional<VaultFile.Opener> opener = context.opener();
        if (!context.vault().exists() || opener.isEmpty()) {
            return "the vault is " + (context.vault().exists() ? "locked" : "not there")
                    + ", so this task's tokens could not be kept and it cannot come back after a reboot";
        }
        try {
            context.vault().update(opener.get(), entries -> {
                final Map<String, VaultEntry> updated = new LinkedHashMap<>(entries);
                updated.keySet().removeIf(name -> reserved(name) && !existing.contains(owner(name)));
                put(updated, container + GATE, tokens.gate());
                put(updated, container + PROVIDER, tokens.provider());
                return updated;
            });
            return "";
        } catch (VaultException ex) {
            return "the vault could not be written (" + ex.getMessage() + "), so this task cannot come back after a reboot";
        }
    }

    /**
     * Reads a task's tokens back.
     *
     * @param container The task's container.
     * @return The tokens - both {@code null} when there is no vault - or empty when the vault is locked or
     *         unreadable.
     */
    public Optional<Tokens> read(String container) {
        if (!context.vault().exists()) {
            // No vault, so nothing was ever kept in it: that is "not kept", not "locked".
            return Optional.of(new Tokens(null, null));
        }
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty()) {
            return Optional.empty();
        }
        try {
            final Map<String, VaultEntry> entries = context.vault().read(opener.get());
            return Optional.of(new Tokens(value(entries.get(PREFIX + container + GATE)),
                    value(entries.get(PREFIX + container + PROVIDER))));
        } catch (VaultException ex) {
            return Optional.empty();
        }
    }

    /**
     * Forgets a task's tokens, as removing the task does.
     *
     * @param container The task's container.
     * @return "" when they are gone or there were none; otherwise why not - the next task's keep prunes them.
     */
    public String forget(String container) {
        final Optional<VaultFile.Opener> opener = context.opener();
        if (!context.vault().exists()) {
            return "";
        }
        if (opener.isEmpty()) {
            return "the vault is locked, so its tokens stay there until a task is next started";
        }
        try {
            context.vault().update(opener.get(), entries -> {
                final Map<String, VaultEntry> updated = new LinkedHashMap<>(entries);
                updated.remove(PREFIX + container + GATE);
                updated.remove(PREFIX + container + PROVIDER);
                return updated;
            });
            return "";
        } catch (VaultException ex) {
            return "its tokens could not be removed from the vault: " + ex.getMessage();
        }
    }

    /**
     * Reads a running task's two tokens from its runtime directory, where the launch left them: the gate's in
     * its resume record, the proxy's in its token file.
     *
     * @param runtime The task's runtime directory.
     * @return What is there.
     */
    public static Tokens fromRuntime(java.nio.file.Path runtime) {
        String gate = null;
        for (final TaskHelpers.Helper helper : TaskHelpers.readFrom(runtime).helpers()) {
            final String token = helper.environment().get(TaskState.GATE_TOKEN);
            if (token != null && !token.isBlank()) {
                gate = token;
            }
        }
        String provider = null;
        try {
            final java.nio.file.Path file = runtime.resolve(PROVIDER_FILE);
            if (java.nio.file.Files.isRegularFile(file)) {
                provider = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8).strip();
            }
        } catch (java.io.IOException ex) {
            // A proxy that wrote no token has none to keep.
        }
        return new Tokens(gate, provider == null || provider.isEmpty() ? null : provider);
    }

    /** The proxy's token file in a task's runtime directory, as {@code vault serve --token-file} writes it. */
    static final String PROVIDER_FILE = "vault.token";

    private static void put(Map<String, VaultEntry> entries, String name, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            entries.put(PREFIX + name, VaultEntry.of(value));
        }
    }

    private static @Nullable String value(@Nullable VaultEntry entry) {
        return entry == null ? null : entry.value();
    }

    // task/<container>/<what>: the container is what is between the prefix and the last slash.
    private static String owner(String name) {
        final String rest = name.substring(PREFIX.length());
        final int slash = rest.lastIndexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

}
