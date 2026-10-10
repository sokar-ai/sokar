package org.fuin.sokar.daemon;

import static org.fuin.sokar.daemon.SokarDaemon.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.app.GateSupport;
import org.fuin.sokar.app.ProjectInventory;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.wire.varlink.VarlinkException;
import org.fuin.sokar.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

/**
 * The daemon's methods for projects, following, backups and clearing.
 * <p>
 * One class per area, so a change to one area's methods is a change to its own file; {@link SokarDaemon} only
 * says which areas there are.
 */
final class ProjectMethods {

    private ProjectMethods() {
    }

    /**
     * Registers this area's methods.
     *
     * @param server Where.
     * @param context The machine.
     * @param inventory The daemon's one task inventory.
     * @param control The daemon's one task control.
     */
    @SuppressWarnings("unused")
    static void register(VarlinkServer server, SokarContext context, TaskInventory inventory, TaskControl control) {
        server.method("WatchProjects", (parameters, replies) -> {
            if (!replies.streaming()) {
                // Same shape as Watch: asked without 'more' it answers once, so one method serves
                // a client that streams and one that cannot.
                replies.last(Map.of("projects", new ProjectInventory(context).projects().stream()
                        .map(summary -> projectRow(context, summary)).toList()));
                return;
            }
            List<Map<String, Object>> previous = null;
            while (replies.open()) {
                final List<Map<String, Object>> projects =
                        new ProjectInventory(context).projects().stream()
                                .map(summary -> projectRow(context, summary)).toList();
                // The whole answer, unlike Watch, which has to strip an age first. Nothing here is
                // derived from a clock: the counts are counts, and 'behindMeasured' moves only when
                // the timer that measures it runs - which is a change worth pushing, because the
                // age an interface draws beside the number resets with it.
                if (!projects.equals(previous)) {
                    replies.more(Map.of("projects", projects));
                    previous = projects;
                }
                sleep(PROJECT_WATCH_INTERVAL);
            }
        });

        server.method("Projects", (parameters, replies) -> {
            // The path in each answer is the one thing a client cannot work out: over a forwarded
            // socket there is no filesystem on this side to look in, and every gate method takes
            // one.
            replies.last(Map.of("projects", new ProjectInventory(context).projects().stream()
                    .map(summary -> projectRow(context, summary)).toList()));
        });

        server.method("RestoreBackup", (parameters, replies) -> {
            final org.fuin.sokar.app.BackupRestore.Result result =
                    org.fuin.sokar.app.BackupRestore.restore(context, text(parameters, "project"),
                            empty(parameters, "repository"),
                            java.nio.file.Path.of(text(parameters, "bundle")),
                            flag(parameters, "dryRun"), flag(parameters, "force"));
            replies.last(Map.of("outcome", result.outcome().name(), "mirror", result.mirror(),
                    "unreviewed", result.unreviewed(), "detail", result.detail()));
        });

        server.method("DeleteBackup", (parameters, replies) -> {
            final org.fuin.sokar.app.BackupRecords records =
                    new org.fuin.sokar.app.BackupRecords(context.paths().gate().backupRecords());
            final String project = text(parameters, "project");
            final java.nio.file.Path bundle =
                    java.nio.file.Path.of(text(parameters, "bundle")).toAbsolutePath();
            final var known = records.of(project).stream()
                    .filter(backup -> backup.bundle().equals(bundle)).findFirst();
            if (known.isEmpty()) {
                replies.last(Map.of("outcome", "NO_SUCH_BACKUP", "fileRemoved", false,
                        "refs", 0, "detail", "no backup of '" + project + "' at " + bundle));
                return;
            }
            if (flag(parameters, "dryRun")) {
                replies.last(Map.of("outcome", "PREVIEWED", "fileRemoved", known.get().present(),
                        "refs", known.get().refs(), "detail", ""));
                return;
            }
            try {
                final boolean removed = java.nio.file.Files.deleteIfExists(bundle);
                records.forget(project, bundle);
                replies.last(Map.of("outcome", "DELETED", "fileRemoved", removed,
                        "refs", known.get().refs(), "detail", ""));
            } catch (java.io.IOException | RuntimeException ex) {
                // The record is left alone when the file could not go: forgetting it would hide a
                // bundle that is still on disk, which is worse than an entry somebody retries.
                replies.last(Map.of("outcome", "FAILED", "fileRemoved", false,
                        "refs", known.get().refs(), "detail", String.valueOf(ex.getMessage())));
            }
        });

        server.method("SyncUpstream", (parameters, replies) -> {
            final org.fuin.sokar.app.UpstreamSync.Result result =
                    org.fuin.sokar.app.UpstreamSync.sync(context, text(parameters, "project"),
                            empty(parameters, "repository"));
            replies.last(Map.of("outcome", result.outcome().name(), "behind", result.behind(),
                    "measured", result.measured(), "reason", result.reason(),
                    "detail", result.detail()));
        });

        server.method("Backups", (parameters, replies) -> {
            replies.last(Map.of("backups",
                    new org.fuin.sokar.app.BackupRecords(context.paths().gate().backupRecords())
                            .of(org.fuin.sokar.app.GateSupport.recordKey(
                                    text(parameters, "project"),
                                    text(parameters, "repository").isEmpty()
                                            ? text(parameters, "project")
                                            : text(parameters, "repository")))
                            .stream()
                            .map(backup -> Map.<String, Object>of(
                                    "taken", backup.taken().toString(),
                                    "bundle", backup.bundle().toString(),
                                    "refs", backup.refs(),
                                    "present", backup.present(),
                                    "bytes", backup.bytes()))
                            .toList()));
        });

        // Following a project's repository. A repository URL is not a secret, so it may travel
        // over this socket where a credential may not.
        server.method("Following", (parameters, replies) -> {
            replies.last(Map.of("projects",
                    new org.fuin.sokar.app.FollowedProjects(context.paths().projects().followed()).all()
                            .stream().map(each -> org.fuin.sokar.app.ProjectInventory.followAsMap(each,
                                    org.fuin.sokar.app.PinnedSigners.of(context.paths().projects().configurationSigners())
                                            .getOrDefault(each.name(), java.util.List.of())))
                            .toList()));
        });

        server.method("RefreshProjects", (parameters, replies) -> {
            final String name = empty(parameters, "project");
            final Map<String, org.fuin.sokar.app.Reconcile.Result> done =
                    new org.fuin.sokar.app.ConfigurationWatch(context, java.time.Duration.ZERO).refresh(name);
            if (done == null) {
                throw new VarlinkException(INTERFACE + ".NoSuchProject", Map.of("project", String.valueOf(name)));
            }
            final Map<String, java.util.List<String>> signers =
                    org.fuin.sokar.app.PinnedSigners.of(context.paths().projects().configurationSigners());
            replies.last(Map.of("projects",
                    new org.fuin.sokar.app.FollowedProjects(context.paths().projects().followed()).all().stream()
                            .filter(each -> done.containsKey(each.name()))
                            .map(each -> org.fuin.sokar.app.ProjectInventory.followAsMap(each,
                                    signers.getOrDefault(each.name(), java.util.List.of())))
                            .toList()));
        });

        server.method("Follow", (parameters, replies) -> {
            final String name = text(parameters, "name");
            final String url = address(parameters, "url");
            final @Nullable String signedBy = parameters.get("signedBy") instanceof String said
                    && !said.isBlank() ? said : null;
            // The same class the command line follows with: a follow over the socket took
            // signedBy and never read it, and refused what the same follow at the terminal applied.
            final org.fuin.sokar.app.FollowSignedBy following =
                    new org.fuin.sokar.app.FollowSignedBy(context);
            final java.util.List<String> signers = parameters.get("signers") instanceof java.util.List<?> given
                    ? given.stream().map(String::valueOf).filter(each -> !each.isBlank()).toList()
                    : java.util.List.of();
            try {
                org.fuin.sokar.app.FollowSignedBy.refuseConflicting(signedBy, signers, flag(parameters, "unverified"));
            } catch (org.fuin.sokar.app.FollowSignedBy.Refused ex) {
                throw new VarlinkException(INTERFACE + ".Failed", Map.of("message", String.valueOf(ex.getMessage())));
            }
            if (flag(parameters, "dryRun")) {
                // Records nothing, not even the named key: the whole point is the question asked
                // BEFORE a project exists here. READY where a real follow would have applied, and
                // otherwise the same named reason it would have given.
                final org.fuin.sokar.app.Reconcile.Result would;
                final java.util.List<String> wouldPin;
                try {
                    final org.fuin.sokar.app.FollowSignedBy.Answer checked = signers.isEmpty()
                            ? following.check(name, url, signedBy, flag(parameters, "unverified"))
                            : following.check(name, url, signers);
                    would = checked.result();
                    final @Nullable String one = signedBy == null || org.fuin.sokar.app.SignedBy.isFingerprint(signedBy)
                            ? null : org.fuin.sokar.app.SignedBy.fingerprintOf(signedBy);
                    wouldPin = !checked.signers().isEmpty() ? checked.signers()
                            : one == null ? java.util.List.of() : java.util.List.of(one);
                } catch (org.fuin.sokar.app.FollowSignedBy.Refused ex) {
                    throw new VarlinkException(INTERFACE + ".Failed",
                            Map.of("message", String.valueOf(ex.getMessage())));
                }
                final boolean ready = would.outcome()
                        == org.fuin.sokar.app.Reconcile.Outcome.APPLIED
                        || would.outcome() == org.fuin.sokar.app.Reconcile.Outcome.UNCHANGED;
                replies.last(Map.of("outcome", ready ? "READY" : would.outcome().name(),
                        "commit", would.commit(), "detail", would.detail(),
                        "refused", would.refused(), "signer", would.signer(),
                        "needsAPerson", would.needsAPerson(),
                        // What to type to store the credential this machine would need. Named by
                        // the machine, because which entry a follow reads is the machine's
                        // knowledge and a client must never guess it - nor ever carry the secret.
                        "storeCommand", would.outcome()
                                == org.fuin.sokar.app.Reconcile.Outcome.NO_CREDENTIAL
                                ? org.fuin.sokar.app.FollowCredential.storeCommand(url) : "",
                        "recorded", false, "pinned", wouldPin));
                return;
            }
            final org.fuin.sokar.app.FollowSignedBy.Answer answer;
            try {
                // Once, now: somebody who asked for this wants to know whether it works, and a
                // refusal at the next tick is one nobody connects to what they did.
                answer = signers.isEmpty()
                        ? following.follow(name, url, signedBy, flag(parameters, "unverified"),
                                flag(parameters, "acceptRewrite"))
                        : following.follow(name, url, signers, flag(parameters, "acceptRewrite"));
            } catch (org.fuin.sokar.app.FollowSignedBy.Refused ex) {
                throw new VarlinkException(INTERFACE + ".Failed",
                        Map.of("message", String.valueOf(ex.getMessage())));
            }
            final org.fuin.sokar.app.Reconcile.Result result = answer.result();
            // When the host's key is what stopped it, what it offered goes with the refusal:
            // a person cannot confirm a fingerprint they have not been shown, and asking them to
            // run a command to see it would be making them fetch what we already have.
            final boolean aboutTheHost = result.outcome()
                    == org.fuin.sokar.app.Reconcile.Outcome.UNKNOWN_HOST_KEY
                    || result.outcome()
                            == org.fuin.sokar.app.Reconcile.Outcome.HOST_KEY_CHANGED;
            // The host is answered rather than left to be read off the address again: a client
            // that re-parsed it would have to know about 'user@host:path', a port, and an alias
            // in somebody's ssh config - three readings of one thing, two of them ours already.
            final String host = aboutTheHost
                    ? String.valueOf(org.fuin.sokar.app.GitCredentialNames.hostOf(url)) : "";
            final java.util.List<Map<String, Object>> offered = aboutTheHost
                    ? org.fuin.sokar.app.HostKeys.offeredBy(context, host)
                            .stream().map(org.fuin.sokar.app.HostKeys.Offered::asMap).toList()
                    : java.util.List.of();
            final Map<String, Object> reply = new java.util.LinkedHashMap<>(Map.of("outcome",
                    result.outcome().name(), "commit", result.commit(),
                    "detail", result.detail(), "refused", result.refused(),
                    "host", host,
                    "hostKeys", offered,
                    "signer", result.signer(), "needsAPerson", result.needsAPerson(),
                    "storeCommand", result.outcome()
                            == org.fuin.sokar.app.Reconcile.Outcome.NO_CREDENTIAL
                            ? org.fuin.sokar.app.FollowCredential.storeCommand(url) : "",
                    // Whether anything was written. False for a first follow that was refused:
                    // the machine is exactly as it was.
                    "recorded", answer.recorded()));
            // Every key this call pinned, by fingerprint, so the interface can tell them in one place.
            reply.put("pinned", !answer.signers().isEmpty() ? answer.signers()
                    : answer.pinned().isEmpty() ? java.util.List.of() : java.util.List.of(answer.pinned()));
            replies.last(reply);
        });

        server.method("HostKeys", (parameters, replies) -> {
            // What a host offers, so a person can compare it with what they were told. Shown,
            // never recorded: recording is TrustHostKey, and it takes the fingerprint back.
            final String host = address(parameters, "host");
            replies.last(Map.of("host", host,
                    "known", org.fuin.sokar.app.HostKeys.known(context, host),
                    "keys", org.fuin.sokar.app.HostKeys.offeredBy(context, host).stream()
                            .map(org.fuin.sokar.app.HostKeys.Offered::asMap).toList()));
        });

        server.method("TrustHostKey", (parameters, replies) -> {
            // The fingerprint comes from the person: they were shown what the host offered and
            // compared it with what they were told out of band. Asking the host again here is not
            // a second opinion - it stops a key that arrived in between from being the one
            // written down.
            final String host = address(parameters, "host");
            final org.fuin.sokar.app.HostKeys.Offered recorded;
            try {
                recorded = org.fuin.sokar.app.HostKeys.trust(context, host,
                        text(parameters, "fingerprint"));
            } catch (java.io.IOException ex) {
                throw new VarlinkException(INTERFACE + ".Failed",
                        Map.of("message", String.valueOf(ex.getMessage())));
            }
            if (recorded == null) {
                replies.last(Map.of("recorded", false, "type", "", "fingerprint", "",
                        "detail", host + " offers no key with that fingerprint right now."
                                + " Nothing was recorded."));
                return;
            }
            replies.last(Map.of("recorded", true, "type", recorded.type(),
                    "fingerprint", recorded.fingerprint(),
                    "detail", host + " is known to this machine from now on"));
        });

        server.method("DefaultRepositories", (parameters, replies) -> {
            final java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (final org.fuin.sokar.app.DefaultProject.Entry entry
                    : new org.fuin.sokar.app.DefaultProject(context).entries()) {
                final Map<String, Object> row = new LinkedHashMap<>(entry.asMap());
                final org.fuin.sokar.app.WorkOrigin.Choice claimed =
                        org.fuin.sokar.app.WorkOrigin.followedNaming(context, entry.upstream());
                row.put("claimedBy", claimed == null ? "" : claimed.project());
                rows.add(row);
            }
            replies.last(Map.of("repositories", rows));
        });

        server.method("AddToDefault", (parameters, replies) -> {
            try {
                final org.fuin.sokar.app.DefaultProject.Entry entry = new org.fuin.sokar.app.DefaultProject(context)
                        .add(address(parameters, "upstream"), empty(parameters, "name"), "");
                final Map<String, Object> row = new LinkedHashMap<>(entry.asMap());
                final org.fuin.sokar.app.WorkOrigin.Choice claimed =
                        org.fuin.sokar.app.WorkOrigin.followedNaming(context, entry.upstream());
                row.put("claimedBy", claimed == null ? "" : claimed.project());
                replies.last(Map.of("repository", row));
            } catch (org.fuin.sokar.app.DefaultProject.Refused ex) {
                throw new VarlinkException(INTERFACE + ".Failed", Map.of("message", String.valueOf(ex.getMessage())));
            }
        });

        server.method("RemoveFromDefault", (parameters, replies) -> {
            final List<org.fuin.sokar.app.DeployKeys.Key> forgotten =
                    new org.fuin.sokar.app.DefaultProject(context).remove(text(parameters, "name"));
            replies.last(Map.of("removed", forgotten != null, "keys", forgotten == null ? List.of()
                    : forgotten.stream().map(org.fuin.sokar.app.DeployKeys.Key::asMap).toList()));
        });

        server.method("Prune", (parameters, replies) -> replies.last(new org.fuin.sokar.app.Prune(context)
                .run(flag(parameters, "apply"), flag(parameters, "includingWork")).asMap()));

        // Everything of a project, or of the account, in one step: the same code as 'sokar clear', so the terminal and
        // an interface clear the same things and answer them in the same words.
        server.method("Clear", (parameters, replies) -> {
            final String project = empty(parameters, "project");
            final org.fuin.sokar.app.Clearing clearing = new org.fuin.sokar.app.Clearing(context);
            final org.fuin.sokar.app.Clearing.Result result = project == null
                    ? clearing.account(flag(parameters, "dryRun"), flag(parameters, "force"))
                    : clearing.project(project, flag(parameters, "dryRun"), flag(parameters, "force"));
            replies.last(result.asMap());
        });

        server.method("Unfollow", (parameters, replies) -> {
            final org.fuin.sokar.app.FollowedProjects projects =
                    new org.fuin.sokar.app.FollowedProjects(context.paths().projects().followed());
            final String name = text(parameters, "name");
            final boolean following = projects.find(name) != null;
            // Read before anything goes: which of this machine's keys the forge holds for it is answered below.
            org.fuin.sokar.core.project.Project known = null;
            try {
                known = org.fuin.sokar.app.GateSupport.byName(context, name);
            } catch (RuntimeException ex) {
                // A project whose file cannot be read has no keys this machine can name.
            }
            // The same refusal 'projects delete' gives, through the same code: a mirror may hold
            // work nobody reviewed, and this must not be the quiet way to destroy it.
            final org.fuin.sokar.app.ProjectDeletion.Result deleted =
                    new org.fuin.sokar.app.ProjectDeletion(context).delete(name,
                            flag(parameters, "dryRun"), flag(parameters, "force"));
            // 'NoSuchProject' for a name this machine does not LIST - not for one it lists but
            // does not follow. Those are projects from before following, and answering the error
            // here left an interface offering to clear one and with nothing to show for it, while
            // force would have swept it blind. Found through the interface; it was hit
            // the same wall on a test machine the same hour.
            if (deleted.outcome() == org.fuin.sokar.app.ProjectDeletion.Outcome.BUILT_IN) {
                throw new VarlinkException(INTERFACE + ".Failed", Map.of("message", String.valueOf(deleted.detail())));
            }
            if (!following && deleted.outcome()
                    == org.fuin.sokar.app.ProjectDeletion.Outcome.NO_SUCH_PROJECT) {
                throw new VarlinkException(INTERFACE + ".NoSuchProject", Map.of("project", name));
            }
            final boolean refused =
                    deleted.outcome() == org.fuin.sokar.app.ProjectDeletion.Outcome.HOLDS_WORK
                    || deleted.outcome()
                            == org.fuin.sokar.app.ProjectDeletion.Outcome.TASKS_RUNNING;
            final List<Map<String, Object>> keys = new java.util.ArrayList<>();
            if (following && !refused && deleted.outcome()
                    != org.fuin.sokar.app.ProjectDeletion.Outcome.PREVIEWED) {
                projects.unfollow(name, context.paths().projects().configurationSigners());
                // This machine's deploy keys for it go from the vault; what they were is answered, so whoever
                // registered them at the forge removes them there.
                if (known != null) {
                    org.fuin.sokar.app.DeployKeys.forget(context, known)
                            .forEach(key -> keys.add(key.asMap()));
                }
            }
            replies.last(Map.of("outcome", deleted.outcome().name(),
                    "keys", keys, "signer", messageKey(context),
                    "unreviewed", deleted.unreviewed(), "running", deleted.running(),
                    // What goes and what is deliberately left alone, filled for a refusal too, so
                    // the cost stands beside the reason. The call this replaced answered both, and
                    // a removal that cannot say what it takes is weaker than the one it replaced.
                    "removes", deleted.removes().stream()
                            .map(removal -> Map.<String, Object>of("kind", removal.kind(),
                                    "what", removal.what()))
                            .toList(),
                    "keeps", deleted.keeps(),
                    // Whether this account was following it at all. A client drawing the
                    // confirmation says "stop following and remove" or just "remove" from this,
                    // rather than guessing from the project it asked about.
                    "following", following,
                    "detail", deleted.detail() == null ? "" : deleted.detail()));
        });

        // A machine's own keys, for an interface that registers them at a forge or writes them into a project:
        // only the public halves ever leave this machine (decided on 2026-09-30).
        server.method("DeployKey", (parameters, replies) -> {
            final String name = text(parameters, "project");
            final String repository = text(parameters, "repository");
            final String upstream = address(parameters, "upstream");
            final Boolean readOnly = parameters.get("readOnly") instanceof Boolean given ? given : null;
            org.fuin.sokar.core.project.Project project = null;
            try {
                project = org.fuin.sokar.app.GateSupport.byName(context, name);
            } catch (RuntimeException ex) {
                if (upstream.isEmpty()) {
                    throw new VarlinkException(INTERFACE + ".NoSuchProject", Map.of("project", name));
                }
            }
            try {
                // A project this machine does not follow yet - a private one, whose key must be at the forge before
                // it can be fetched at all - takes the upstream from the caller.
                final org.fuin.sokar.app.DeployKeys.Key key = project == null
                        ? org.fuin.sokar.app.DeployKeys.make(context, context.opener().orElse(null), name,
                                repository.isEmpty() ? name : repository, upstream,
                                repository.isEmpty() || repository.equals(name), readOnly, flag(parameters, "new"))
                        : org.fuin.sokar.app.DeployKeys.make(context, project, repository.isEmpty() ? null : repository,
                                readOnly, flag(parameters, "new"));
                replies.last(Map.of("key", key.asMap()));
            } catch (org.fuin.sokar.app.DeployKeys.Refused ex) {
                throw new VarlinkException(INTERFACE + ".DeployKeyRefused",
                        Map.of("message", String.valueOf(ex.getMessage())));
            }
        });

        server.method("ProjectFileSchema", (parameters, replies) -> replies.last(Map.of(
                "sections", org.fuin.sokar.core.project.ProjectReader.schema(),
                "open", org.fuin.sokar.core.project.ProjectReader.openSections(),
                "keys", org.fuin.sokar.core.project.ProjectReader.keys().stream()
                        .map(org.fuin.sokar.core.project.ProjectKey::asMap).toList())));

        server.method("CheckProjectFile", (parameters, replies) -> replies.last(
                org.fuin.sokar.app.ProjectFileCheck.check(context, text(parameters, "text")).asMap()));
    }
}
