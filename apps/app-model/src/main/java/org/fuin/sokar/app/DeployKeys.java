package org.fuin.sokar.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.credential.Credential;
import org.fuin.sokar.core.credential.CredentialRegistry;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.Repository;
import org.fuin.sokar.vault.AgentKey;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.VaultEntry;
import org.jspecify.annotations.Nullable;

/**
 * This machine's deploy keys: one per repository of a project, made here, its secret half in the vault and
 * declared for that repository's upstream, its public half handed out for a person to register at the forge.
 * <p>
 * <strong>Read-only for the project's own repository</strong> (decided on 2026-09-30): a
 * machine reads its project's configuration and never writes it, so a compromised machine cannot change it;
 * only a work repository, where the host pushes approved work, is registered with write access. The access is
 * the forge's to enforce - what this says is how the key is to be registered.
 */
public final class DeployKeys {

    /**
     * A deploy key, as a person or an interface registers it.
     *
     * @param entry Its vault entry.
     * @param repository The repository it is for.
     * @param upstream That repository's upstream.
     * @param publicKey The public half, {@code ssh-ed25519 AAAA... comment}.
     * @param fingerprint Its fingerprint, {@code SHA256:...}.
     * @param title What the forge should call it: {@code sokar <machine> <project>/<repository>}.
     * @param writeAccess Whether it is to be registered with write access.
     * @param made Whether it was made just now, rather than found.
     */
    public record Key(String entry, String repository, String upstream, String publicKey, String fingerprint,
            String title, boolean writeAccess, boolean made) {

        /**
         * Returns it as plain values, for a wire.
         *
         * @return The key.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("entry", entry);
            map.put("repository", repository);
            map.put("upstream", upstream);
            map.put("publicKey", publicKey);
            map.put("fingerprint", fingerprint);
            map.put("title", title);
            map.put("writeAccess", writeAccess);
            map.put("made", made);
            return map;
        }
    }

    /** Why a deploy key cannot be had. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    private DeployKeys() {
    }

    /**
     * Makes this machine's deploy key for a repository, or returns the one it has.
     *
     * @param context Where the vault and the declarations are.
     * @param project The project.
     * @param repository The repository, or {@code null} for the project's own.
     * @param readOnly Whether it is to be read-only; {@code null} for the default - read-only for the project's
     *        own repository, write access for a work repository.
     * @param fresh Whether to make a new one although this machine has one.
     * @return The key.
     * @throws Refused If the repository has no upstream a key can be for, or the vault cannot be used.
     */
    public static Key make(SokarContext context, Project project, @Nullable String repository,
            @Nullable Boolean readOnly, boolean fresh) throws Refused {
        return make(context, context.opener().orElse(null), project, repository, readOnly, fresh);
    }

    /**
     * Makes this machine's deploy key for a repository with the vault opened as the caller could, or returns the
     * one it has.
     *
     * @param context Where the vault and the declarations are.
     * @param way How the vault is opened: the keyring's for the daemon, a passphrase asked at a terminal for
     *        the command; {@code null} when it cannot be.
     * @param project The project.
     * @param repository The repository, or {@code null} for the project's own.
     * @param readOnly Whether it is to be read-only; {@code null} for the default.
     * @param fresh Whether to make a new one although this machine has one.
     * @return The key.
     * @throws Refused If the repository has no upstream a key can be for, or the vault cannot be used.
     */
    public static Key make(SokarContext context, org.fuin.sokar.vault.VaultFile.@Nullable Opener way,
            Project project, @Nullable String repository, @Nullable Boolean readOnly, boolean fresh)
            throws Refused {
        final Repository chosen = GateSupport.repository(project, repository);
        // The project's own repository, its file naming no upstream - a guarded or offline project's planning - is
        // still fetched from where this machine follows it, and that is the key it needs.
        final String upstream = chosen.upstream() != null ? chosen.upstream()
                : chosen.name().equals(project.name()) ? followedFrom(context, project.name()) : null;
        if (upstream == null) {
            throw new Refused("'" + chosen.name() + "' has no upstream, so there is no forge to give a key to");
        }
        return make(context, way, project.name(), chosen.name(), upstream, chosen.name().equals(project.name()),
                readOnly, fresh);
    }

    /** Where this machine follows a project from; {@code null} when it does not, or the record cannot be read. */
    private static @Nullable String followedFrom(final SokarContext context, final String project) {
        try {
            return new FollowedProjects(context.paths().projects().followed()).all().stream()
                    .filter(followed -> followed.name().equals(project)).map(FollowedProjects.Followed::url)
                    .findFirst().orElse(null);
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    /**
     * Makes this machine's deploy key for a repository of a project it does not follow yet - a private one, whose
     * key has to be registered at the forge before this machine can fetch it at all - or returns the one it has.
     *
     * @param context Where the vault and the declarations are.
     * @param way How the vault is opened, or {@code null} when it cannot be.
     * @param projectName The project's name, as it will be followed.
     * @param repositoryName The repository's name; the project's own is named after the project.
     * @param upstream The repository's upstream.
     * @param own Whether it is the project's own repository.
     * @param readOnly Whether it is to be read-only; {@code null} for the default.
     * @param fresh Whether to make a new one although this machine has one.
     * @return The key.
     * @throws Refused If nothing authenticates to the upstream, or the vault cannot be used.
     */
    public static Key make(SokarContext context, org.fuin.sokar.vault.VaultFile.@Nullable Opener way,
            String projectName, String repositoryName, String upstream, boolean own, @Nullable Boolean readOnly,
            boolean fresh) throws Refused {
        final String entry = entry(projectName, repositoryName);
        final String machine = "sokar@" + hostName();
        // What it would be declared as, before any key is made: an upstream nothing authenticates to - a path on
        // this machine - is refused with nothing left behind in the vault.
        final Credential declared;
        try {
            declared = CredentialDeclarations.named(new Credential(entry, Credential.Kind.SSH_KEY,
                    CredentialRegistry.normalise(upstream), null, Credential.ANY, Credential.Source.VAULT));
            CredentialDeclarations.checkPossible(declared);
        } catch (org.fuin.sokar.core.credential.CredentialException ex) {
            throw new Refused("no deploy key for " + upstream + ": " + ex.getMessage());
        }
        final java.util.Optional<org.fuin.sokar.vault.VaultFile.Opener> opener = java.util.Optional.ofNullable(way);
        if (opener.isEmpty()) {
            throw new Refused("the vault is locked, and a deploy key's secret half goes into it; 'sokar vault unlock'"
                    + " at the machine");
        }
        final VaultEntry existing;
        final AgentKey key;
        try {
            existing = context.vault().exists() ? kept(context.vault().read(opener.get()), projectName,
                    repositoryName, upstream) : null;
            if (existing != null && !fresh) {
                key = org.fuin.sokar.vault.StoredKey.of(existing.value(), machine);
                // Under its own name from now on, if it was kept under the old one.
                final String old = legacy(projectName, repositoryName);
                context.vault().update(opener.get(), all -> {
                    if (all.get(old) == existing) {
                        all.remove(old);
                    }
                    all.putIfAbsent(entry, existing);
                    return all;
                });
            } else {
                final SigningKey made = SigningKey.generate(machine);
                key = made;
                final VaultEntry kept = new VaultEntry(made.seedBase64(), "ssh-key",
                        Map.of("upstream", upstream, "made_on", machine));
                context.vault().update(opener.get(), all -> {
                    all.put(entry, kept);
                    return all;
                });
            }
        } catch (org.fuin.sokar.vault.VaultException ex) {
            throw new Refused("the vault cannot be used: " + ex.getMessage());
        }
        try {
            new CredentialDeclarations(context).declare(declared);
        } catch (org.fuin.sokar.core.credential.CredentialException | java.io.IOException ex) {
            throw new Refused("the key is in the vault as '" + entry + "', and could not be declared for " + upstream
                    + ": " + ex.getMessage());
        }
        final boolean write = readOnly == null ? !own : !readOnly;
        final String line = key.authorizedKeysLine();
        final String fingerprint = SignedBy.fingerprintOf(line);
        return new Key(entry, repositoryName, upstream, line, fingerprint == null ? "" : fingerprint,
                "sokar " + hostName() + " " + projectName + "/" + repositoryName, write, existing == null || fresh);
    }

    /**
     * Forgets this machine's deploy keys for a project: their vault entries and declarations. What they were
     * is answered, so whoever registered them at the forge removes them there.
     *
     * @param context Where the vault and the declarations are.
     * @param project The project.
     * @return The keys that were forgotten, by title and fingerprint.
     */
    public static List<Key> forget(SokarContext context, Project project) {
        return forget(context, project, null);
    }

    /**
     * Returns this machine's deploy keys for a project, as {@link #forget(SokarContext, Project)} would answer them,
     * and forgets nothing: what a dry run of a clearing names for a person to remove at the forge.
     *
     * @param context Where the vault is.
     * @param project The project.
     * @return The keys the vault holds for it; none when it is locked or there is none.
     */
    public static List<Key> held(SokarContext context, Project project) {
        final List<Key> held = new ArrayList<>();
        final java.util.Optional<org.fuin.sokar.vault.VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty() || !context.vault().exists()) {
            return held;
        }
        final String machine = "sokar@" + hostName();
        try {
            final Map<String, VaultEntry> all = context.vault().read(opener.get());
            for (final Repository repository : repositories(project)) {
                final VaultEntry kept = kept(all, project.name(), repository.name(), repository.upstream());
                if (kept == null) {
                    continue;
                }
                final String entry = all.get(entry(project.name(), repository.name())) == kept
                        ? entry(project.name(), repository.name()) : legacy(project.name(), repository.name());
                final String line = org.fuin.sokar.vault.StoredKey.of(kept.value(), machine).authorizedKeysLine();
                final String fingerprint = SignedBy.fingerprintOf(line);
                held.add(new Key(entry, repository.name(), kept.settings().getOrDefault("upstream", ""), line,
                        fingerprint == null ? "" : fingerprint,
                        "sokar " + hostName() + " " + project.name() + "/" + repository.name(),
                        !repository.name().equals(project.name()), false));
            }
        } catch (org.fuin.sokar.vault.VaultException ex) {
            return List.of();
        }
        return held;
    }

    /**
     * Forgets this machine's deploy key for one repository of a project - one taken out of {@code default}, say - and
     * answers it, so whoever registered it at the forge removes it there.
     *
     * @param context Where the vault and the declarations are.
     * @param project The project.
     * @param only The repository, or {@code null} for all of them.
     * @return The keys that were forgotten.
     */
    public static List<Key> forget(SokarContext context, Project project, @Nullable String only) {
        final List<Key> forgotten = new ArrayList<>();
        final java.util.Optional<org.fuin.sokar.vault.VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty() || !context.vault().exists()) {
            return forgotten;
        }
        final String machine = "sokar@" + hostName();
        try {
            final Map<String, VaultEntry> all = context.vault().read(opener.get());
            final List<String> names = new ArrayList<>();
            for (final Repository repository : repositories(project)) {
                if (only != null && !only.equals(repository.name())) {
                    continue;
                }
                final VaultEntry held = kept(all, project.name(), repository.name(), repository.upstream());
                if (held == null) {
                    continue;
                }
                final String entry = all.get(entry(project.name(), repository.name())) == held
                        ? entry(project.name(), repository.name()) : legacy(project.name(), repository.name());
                final String line = org.fuin.sokar.vault.StoredKey.of(held.value(), machine).authorizedKeysLine();
                final String fingerprint = SignedBy.fingerprintOf(line);
                forgotten.add(new Key(entry, repository.name(), held.settings().getOrDefault("upstream", ""), line,
                        fingerprint == null ? "" : fingerprint,
                        "sokar " + hostName() + " " + project.name() + "/" + repository.name(),
                        !repository.name().equals(project.name()), false));
                names.add(entry);
                if (repository.upstream() != null) {
                    try {
                        new CredentialDeclarations(context).forget(CredentialRegistry.normalise(repository.upstream()));
                    } catch (java.io.IOException | RuntimeException ex) {
                        // The record is harmless without its key; the key is what grants access.
                    }
                }
            }
            if (!names.isEmpty()) {
                context.vault().update(opener.get(), entries -> {
                    names.forEach(entries::remove);
                    return entries;
                });
            }
        } catch (org.fuin.sokar.vault.VaultException ex) {
            // Locked or unreadable: nothing is forgotten, and nothing is said to have been.
            return List.of();
        }
        return forgotten;
    }

    private static List<Repository> repositories(Project project) {
        final List<Repository> all = new ArrayList<>();
        all.add(GateSupport.repository(project, null));
        project.repositories().stream().filter(each -> !each.name().equals(project.name())).forEach(all::add);
        return all;
    }

    /**
     * Returns the vault entry a repository's deploy key is kept under.
     * <p>
     * With a separator neither name can hold: joined with hyphens, 'web' with 'app-api' and 'web-app' with 'api' were
     * one entry - the second was given the first one's key, and clearing 'web' deleted 'web-app''s.
     *
     * @param project The project.
     * @param repository The repository.
     * @return The entry's name.
     */
    static String entry(String project, String repository) {
        return "deploy:" + project + ":" + repository;
    }

    /** The name a key was kept under before, which two projects whose names meet could share. */
    private static String legacy(String project, String repository) {
        return "deploy-" + project + "-" + repository;
    }

    /**
     * Returns the key a repository has: under its own name, or under the old one only when it was made for this
     * repository's upstream - a project whose names only meet another's does not inherit its key.
     */
    private static @Nullable VaultEntry kept(Map<String, VaultEntry> all, String project, String repository,
            @Nullable String upstream) {
        final VaultEntry own = all.get(entry(project, repository));
        if (own != null) {
            return own;
        }
        final VaultEntry old = all.get(legacy(project, repository));
        return old != null && upstream != null && upstream.equals(old.settings().get("upstream")) ? old : null;
    }

    static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "localhost";
        }
    }
}
