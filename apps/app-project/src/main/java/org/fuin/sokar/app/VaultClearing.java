package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.runtime.ContainerSummary;
import org.fuin.sokar.vault.KernelKeyring;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultException;
import org.fuin.sokar.vault.VaultFile;
import org.jspecify.annotations.Nullable;

/**
 * Clears the vault from this machine in one step, and answers what it did: the file, its backup and its lock, what
 * the kernel keyring caches of it, and - first - what the transports keep with its secrets.
 * <p>
 * Cleared by hand, the vault left a homeserver knowing an account Sokar held no token for any more, and the next
 * start of a project with messages was refused. So a transport clears a project's account on a server elsewhere
 * while the vault still holds its secrets, and its account-wide {@code clear} runs once the vault is gone.
 * <p>
 * <strong>A running task that holds a token from the vault stops it, even with force:</strong> its proxy holds what
 * it read, and a task that cannot come back after a reboot is not what a person asked for. Stopping it is theirs.
 * <p>
 * The terminal and the daemon both call this, so the two cannot clear different things.
 */
public final class VaultClearing {

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    public VaultClearing(final SokarContext context) {
        this.context = context;
    }

    /** A project whose conversation is on a server it names itself, and what it says to the transport. */
    private record Elsewhere(String project, String scheme, Map<String, Object> settings) {
    }

    /**
     * Clears the vault.
     *
     * @param dryRun Whether to only say what would go.
     * @param force Whether to clear a vault that is locked, or whose transports could not clear a project's account.
     * @return What it did; no items for an account with nothing to clear.
     */
    public Clearing.Result clear(final boolean dryRun, final boolean force) {
        final List<Clearing.Item> items = new ArrayList<>();
        final VaultFile vault = context.vault();
        final boolean exists = vault.exists();
        final @Nullable Map<String, VaultEntry> entries = exists ? entries(vault) : null;
        final boolean unread = exists && entries == null;

        boolean refused = exists && tasksHolding(entries, items);
        if (unread) {
            items.add(new Clearing.Item("vault", "what " + vault.path() + " holds", force
                    ? dryRun ? Clearing.Status.WOULD_REMOVE : Clearing.Status.REMOVED : Clearing.Status.NOT_REMOVED,
                    "it is locked, so its credentials, grants, deploy keys and the accounts a transport keeps cannot"
                            + " be named, and no transport can clear an account on another server without them"
                            + (force ? "" : "; 'sokar vault unlock' and clear again, or clear it unread with --force")));
            refused |= !force;
        }
        if (refused && !dryRun) {
            return new Clearing.Result(List.copyOf(items), List.of(), "", true);
        }

        final List<Project> projects = projects();
        final List<DeployKeys.Key> keys = new ArrayList<>();
        final List<Clearing.Item> held = entries == null ? List.of() : held(entries, projects, keys);

        boolean failed = false;
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        for (final Elsewhere each : elsewhere(projects)) {
            final String what = "the " + each.scheme() + " account of " + each.project()
                    + " on the server the project names";
            if (dryRun) {
                items.add(new Clearing.Item("conversation", what, Clearing.Status.WOULD_REMOVE, ""));
                continue;
            }
            try {
                lifecycle.clear(each.scheme(), each.project(), each.settings()).forEach(
                        said -> items.add(new Clearing.Item("conversation", said, Clearing.Status.REMOVED, "")));
                items.add(new Clearing.Item("conversation", what, Clearing.Status.REMOVED, ""));
            } catch (final TransportLifecycle.Refused ex) {
                items.add(new Clearing.Item("conversation", what, Clearing.Status.NOT_REMOVED,
                        String.valueOf(ex.getMessage())));
                failed = true;
            }
        }
        if (failed && !force) {
            // Without the vault nobody could clear that account later: its token goes with it.
            items.add(new Clearing.Item("vault", vault.path().toString(), Clearing.Status.NOT_REMOVED,
                    "a transport could not clear an account it keeps with the vault's secrets; nothing of the vault"
                            + " was removed - clear it with --force to remove it all the same, and that account stays"));
            return new Clearing.Result(List.copyOf(items), List.copyOf(keys), "", true);
        }

        final boolean gone = files(vault, dryRun, items);
        for (final Clearing.Item item : held) {
            items.add(item.status() == Clearing.Status.FOR_A_PERSON ? item
                    : new Clearing.Item(item.kind(), item.what(), dryRun ? Clearing.Status.WOULD_REMOVE
                            : gone ? Clearing.Status.REMOVED : Clearing.Status.NOT_REMOVED,
                            dryRun || gone ? "" : "the vault file stays"));
        }
        keyring(dryRun, items);
        if (gone || dryRun) {
            new Clearing(context).transports(dryRun, items);
        }
        return new Clearing.Result(List.copyOf(items), List.copyOf(keys), "", refused);
    }

    /** The vault's entries, or {@code null} when it is locked or cannot be read. */
    private @Nullable Map<String, VaultEntry> entries(final VaultFile vault) {
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty()) {
            return null;
        }
        try {
            return vault.read(opener.get());
        } catch (final VaultException ex) {
            // A wrong passphrase cached, or a damaged file: what it holds cannot be named either way.
            return null;
        }
    }

    /**
     * Names every running task that holds a token from the vault, and answers whether there is one. With the vault
     * unread every running task is named: whether it holds one cannot be told.
     */
    private boolean tasksHolding(final @Nullable Map<String, VaultEntry> entries, final List<Clearing.Item> items) {
        final List<ContainerSummary> tasks;
        try {
            tasks = context.podman().sokarTasks();
        } catch (final RuntimeException ex) {
            items.add(new Clearing.Item("task", "the tasks on this machine", Clearing.Status.NOT_REMOVED,
                    "whether one runs with a token from the vault cannot be asked: " + ex.getMessage()));
            return true;
        }
        boolean holding = false;
        for (final ContainerSummary task : tasks) {
            if (!task.running() || entries != null && entries.keySet().stream()
                    .noneMatch(name -> name.startsWith(VaultNames.TASK_PREFIX + task.name() + "/"))) {
                continue;
            }
            items.add(new Clearing.Item("task", task.name(), Clearing.Status.NOT_REMOVED,
                    (entries == null ? "it runs, and whether it holds a token from the vault cannot be read"
                            : "it runs with a token from the vault")
                            + "; stop it with 'sokar task stop " + task.name() + "' first - --force does not clear"
                            + " past a running task"));
            holding = true;
        }
        return holding;
    }

    /** Every project this machine can read. */
    private List<Project> projects() {
        final List<Project> projects = new ArrayList<>();
        for (final ProjectInventory.Summary summary : new ProjectInventory(context).projects()) {
            try {
                projects.add(GateSupport.byName(context, summary.name()));
            } catch (final RuntimeException ex) {
                // A project whose file cannot be read names no server and no keys this machine can find.
            }
        }
        return projects;
    }

    /**
     * Every project whose conversation is configured with settings of its own: a server elsewhere, whose account the
     * vault holds the token for. What the settings mean is the transport's; a project that says nothing is on the
     * account's own server, which the account-wide clear removes.
     */
    private static List<Elsewhere> elsewhere(final List<Project> projects) {
        final List<Elsewhere> found = new ArrayList<>();
        for (final Project project : projects) {
            project.mail().transports().forEach((scheme, said) -> {
                if (said instanceof Map<?, ?> settings && !settings.isEmpty()) {
                    found.add(new Elsewhere(project.name(), scheme, cast(settings)));
                }
            });
        }
        return found;
    }

    /** What the vault holds, named; the deploy keys among it are added to {@code keys}, for a person. */
    private List<Clearing.Item> held(final Map<String, VaultEntry> entries, final List<Project> projects,
            final List<DeployKeys.Key> keys) {
        final List<Clearing.Item> held = new ArrayList<>();
        for (final Project project : projects) {
            keys.addAll(DeployKeys.held(context, project));
        }
        final Set<String> deployKeys = new TreeSet<>();
        keys.forEach(key -> deployKeys.add(key.entry()));
        final Set<String> tasks = new TreeSet<>();
        for (final String name : new TreeSet<>(entries.keySet())) {
            if (name.startsWith(VaultNames.GRANT_PREFIX)) {
                held.add(new Clearing.Item("grant", name.substring(VaultNames.GRANT_PREFIX.length()),
                        Clearing.Status.WOULD_REMOVE, ""));
            } else if (name.startsWith(VaultNames.TRANSPORT_PREFIX)) {
                held.add(new Clearing.Item("transport account", name.substring(VaultNames.TRANSPORT_PREFIX.length()),
                        Clearing.Status.WOULD_REMOVE, ""));
            } else if (name.startsWith(VaultNames.TASK_PREFIX)) {
                final String rest = name.substring(VaultNames.TASK_PREFIX.length());
                tasks.add(rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest);
            } else if (!deployKeys.contains(name)) {
                held.add(new Clearing.Item("credential", name, Clearing.Status.WOULD_REMOVE, ""));
            }
        }
        tasks.forEach(task -> held.add(new Clearing.Item("task token", task, Clearing.Status.WOULD_REMOVE, "")));
        for (final DeployKeys.Key key : keys) {
            held.add(new Clearing.Item("deploy key", "'" + key.title() + "' " + key.fingerprint() + " on "
                    + key.upstream(), Clearing.Status.FOR_A_PERSON,
                    "it goes with the vault, and the forge still grants access by it; remove it there"));
        }
        return held;
    }

    /** Removes the vault file, its backup and its lock, and answers whether the vault file is gone. */
    private boolean files(final VaultFile vault, final boolean dryRun, final List<Clearing.Item> items) {
        final Path file = vault.path();
        final String devices = devices(vault);
        boolean gone = true;
        for (final String[] each : new String[][] { { "vault", "" }, { "backup", ".old" }, { "lock", ".lock" } }) {
            final Path path = each[1].isEmpty() ? file : file.resolveSibling(file.getFileName() + each[1]);
            if (!Files.exists(path)) {
                continue;
            }
            final String what = path + (each[1].isEmpty() ? devices : "");
            if (dryRun) {
                items.add(new Clearing.Item(each[0], what, Clearing.Status.WOULD_REMOVE, ""));
                continue;
            }
            try {
                Files.delete(path);
                items.add(new Clearing.Item(each[0], what, Clearing.Status.REMOVED, ""));
            } catch (final IOException ex) {
                items.add(new Clearing.Item(each[0], what, Clearing.Status.NOT_REMOVED, String.valueOf(ex.getMessage())));
                gone &= !each[1].isEmpty();
            }
        }
        return gone;
    }

    /** How many devices the vault has keyslots for, said after its path: they go with the file. */
    private static String devices(final VaultFile vault) {
        if (!vault.exists()) {
            return "";
        }
        try {
            final long count = vault.slots().stream().filter(slot -> !slot.recovery()).count();
            return count == 0 ? "" : ", with the keyslot" + (count == 1 ? "" : "s") + " of " + count + " device"
                    + (count == 1 ? "" : "s");
        } catch (final VaultException ex) {
            return "";
        }
    }

    /** Forgets the passphrase and the device's share the kernel keyring caches for this vault. */
    private void keyring(final boolean dryRun, final List<Clearing.Item> items) {
        if (!KernelKeyring.available()) {
            return;
        }
        final String passphrase = "the passphrase cached in the kernel keyring";
        final String share = "a device's share cached in the kernel keyring";
        final KernelKeyring cached = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        if (dryRun) {
            final Optional<char[]> read = cached.read();
            read.ifPresent(value -> java.util.Arrays.fill(value, '\0'));
            if (read.isPresent()) {
                items.add(new Clearing.Item("keyring", passphrase, Clearing.Status.WOULD_REMOVE, ""));
            }
            final Optional<byte[]> held = VaultShare.held(context.paths());
            held.ifPresent(value -> java.util.Arrays.fill(value, (byte) 0));
            if (held.isPresent()) {
                items.add(new Clearing.Item("keyring", share, Clearing.Status.WOULD_REMOVE, ""));
            }
            return;
        }
        forgotten(passphrase, cached.forget(), items);
        forgotten(share, VaultShare.forget(context.paths()), items);
    }

    private static void forgotten(final String what, final KernelKeyring.Forgotten forgotten,
            final List<Clearing.Item> items) {
        switch (forgotten) {
            case CLEARED -> items.add(new Clearing.Item("keyring", what, Clearing.Status.REMOVED, ""));
            // Never said as gone: a secret nobody could check may still be in the keyring.
            case UNKNOWN -> items.add(new Clearing.Item("keyring", what, Clearing.Status.NOT_REMOVED,
                    "the keyring did not answer; check 'keyctl show @u' and treat it as still cached"));
            case NOTHING_CACHED -> {
                // Nothing was there, so nothing is named.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(final Map<?, ?> said) {
        return (Map<String, Object>) said;
    }
}
