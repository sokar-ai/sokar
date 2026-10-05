package org.fuin.sokar.daemon;

import static org.fuin.sokar.daemon.SokarDaemon.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.wire.varlink.VarlinkException;
import org.fuin.sokar.wire.varlink.VarlinkServer;

/**
 * The daemon's methods for the vault, credentials, grants and destinations.
 * <p>
 * One class per area, so a change to one area's methods is a change to its own file; {@link SokarDaemon} only
 * says which areas there are.
 */
final class VaultMethods {

    private VaultMethods() {
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
        server.method("VaultClear", (parameters, replies) -> {
            final org.fuin.sokar.app.Clearing.Result result = new org.fuin.sokar.app.VaultClearing(context)
                    .clear(flag(parameters, "dryRun"), flag(parameters, "force"));
            replies.last(Map.of("items", result.items().stream().map(org.fuin.sokar.app.Clearing.Item::asMap).toList(),
                    "keys", result.keys().stream().map(org.fuin.sokar.app.DeployKeys.Key::asMap).toList(),
                    "refused", result.refused()));
        });

        server.method("ImportCredential", (parameters, replies) -> {
            // No console tier: a daemon has no terminal to ask at, so a vault that is not already
            // unlocked is answered as VAULT_LOCKED rather than hanging on a prompt nobody sees.
            // A device's share counts as unlocked, which is the one way a daemon can open a vault.
            final org.fuin.sokar.app.CredentialImport.Result result =
                    org.fuin.sokar.app.CredentialImport.run(context,
                            absent(parameters, "agent"), absent(parameters, "configDirectory"),
                            context::opener);
            replies.last(Map.of("outcome", result.outcome().name(), "name", result.name(),
                    "type", result.type(), "length", result.length(),
                    "source", result.source(), "detail", result.detail()));
        });

        server.method("Credentials", (parameters, replies) -> {
            // Names, types and lengths - never a value. The vault is read only if the passphrase
            // is already in the kernel keyring: a daemon has no terminal to ask at, and a call
            // that blocked on a prompt nobody can see would hang the interface.
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("vault", context.vault().path().toString());
            answer.put("exists", context.vault().exists());
            final var readable = context.readableCredentials();
            final Map<String, org.fuin.sokar.vault.VaultEntry> grants = context.readableGrants().orElseGet(Map::of);
            final List<Map<String, Object>> entries = readable.orElseGet(Map::of).entrySet()
                    .stream()
                    .map(entry -> {
                        final Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", entry.getKey());
                        row.put("type", entry.getValue().type() == null
                                ? "" : entry.getValue().type());
                        row.put("characters", entry.getValue().value().length());
                        // Configuration, never a secret: a client id, a token URL, scopes.
                        row.put("settings", entry.getValue().settings());
                        // Who granted it and when, for an entry a person grants; the grant itself never.
                        final org.fuin.sokar.vault.VaultEntry grant = grants.get(entry.getKey());
                        if (grant != null && org.fuin.sokar.supervisor.Grants.isGrant(entry.getValue().type())) {
                            row.put("grant", Map.of("grantedBy", grant.settings().getOrDefault("granted_by", ""),
                                    "grantedAt", grant.settings().getOrDefault("granted_at", "")));
                        }
                        return row;
                    }).toList();
            answer.put("credentials", entries);
            // What this machine is CONFIGURED to connect with, which is a different list: it is
            // readable with the vault shut, because it holds no secret. Without it, "a credential
            // for this host is configured, unlock the vault" and "nothing is here" look the same.
            answer.put("connections",
                    new org.fuin.sokar.app.CredentialDeclarations(context).asMaps());
            // Empty because it is locked and empty because it holds nothing are different things
            // an interface has to show apart. Asked of the vault rather than inferred from the
            // list being empty, which is what this did before: an unlocked vault holding nothing
            // answered "unreadable", and a client acting on that told somebody to unlock a vault
            // that was already open.
            answer.put("readable", readable.isPresent());
            replies.last(answer);
        });

        // The four keyslot verbs. The daemon can open the vault with a share where it cannot with a
        // passphrase: a share arrives from a device over this socket, and a passphrase would need
        // a terminal the daemon does not have.
        server.method("Keyslots", (parameters, replies) -> {
            final org.fuin.sokar.app.Keyslots keyslots = new org.fuin.sokar.app.Keyslots(context);
            replies.last(Map.of("slots", keyslots.list().stream()
                    .map(slot -> slotAsMap(slot, context)).toList()));
        });

        server.method("EnrollDevice", (parameters, replies) -> {
            final org.fuin.sokar.app.Keyslots.Enrollment result =
                    new org.fuin.sokar.app.Keyslots(context).enroll(text(parameters, "name"),
                            text(parameters, "share"), text(parameters, "storage"));
            final Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put("outcome", result.outcome().name());
            answer.put("detail", result.detail());
            if (result.slot() != null) {
                answer.put("slot", slotAsMap(result.slot(), context));
            }
            replies.last(answer);
        });

        server.method("RevokeKeyslot", (parameters, replies) -> {
            final org.fuin.sokar.app.Keyslots.Revocation result =
                    new org.fuin.sokar.app.Keyslots(context).revoke(text(parameters, "id"));
            replies.last(Map.of("outcome", result.outcome().name(),
                    "remaining", result.remaining().stream()
                            .map(slot -> slotAsMap(slot, context)).toList(),
                    "detail", result.detail()));
        });

        server.method("UnlockWithShare", (parameters, replies) -> {
            final org.fuin.sokar.app.Keyslots.Unlock result =
                    new org.fuin.sokar.app.Keyslots(context).unlock(text(parameters, "share"),
                            parameters.get("minutes") instanceof Number minutes
                                    ? minutes.intValue() : null);
            final Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put("outcome", result.outcome().name());
            answer.put("until", result.until());
            answer.put("detail", result.detail());
            if (result.slot() != null) {
                answer.put("slot", slotAsMap(result.slot(), context));
            }
            replies.last(answer);
        });

        server.method("CredentialDeclare", (parameters, replies) -> {
            // No secret crosses this socket. What is written here is the readable half, and
            // 'storeCommand' says what to type on the machine for the value.
            final org.fuin.sokar.core.credential.Credential.Source source;
            final org.fuin.sokar.core.credential.Credential.Kind kind;
            try {
                final String said = text(parameters, "source");
                source = said.isEmpty()
                        ? org.fuin.sokar.core.credential.Credential.Source.VAULT
                        : org.fuin.sokar.core.credential.Credential.Source.valueOf(
                                said.toUpperCase(java.util.Locale.ROOT));
                kind = org.fuin.sokar.core.credential.Credential.Kind.valueOf(
                        text(parameters, "kind").toUpperCase(java.util.Locale.ROOT)
                                .replace('-', '_'));
            } catch (IllegalArgumentException ex) {
                throw new VarlinkException(INTERFACE + ".Failed", Map.of("message",
                        "kind is SSH_KEY, TOKEN, BASIC or OAUTH and source is VAULT, FILE,"
                                + " ENVIRONMENT or AGENT"));
            }
            final String match = text(parameters, "match");
            if (match.isEmpty()) {
                throw new VarlinkException(INTERFACE + ".Failed",
                        Map.of("message", "a credential has to say which destinations it covers"));
            }
            final String purpose = text(parameters, "purpose");
            final String normalised =
                    org.fuin.sokar.core.credential.CredentialRegistry.normalise(match);
            final String id = text(parameters, "id");
            // 'id' is optional in the contract, and a client that leaves it out for the vault
            // means "you name it". Named by CredentialDeclarations, which is the one place that
            // does it - the terminal path had its own copy of this and did not have the fix.
            final org.fuin.sokar.core.credential.Credential declared;
            try {
                declared = org.fuin.sokar.app.CredentialDeclarations.named(
                        new org.fuin.sokar.core.credential.Credential(id, kind, normalised,
                                text(parameters, "user").isEmpty()
                                        ? null : text(parameters, "user"),
                                purpose.isEmpty()
                                        ? org.fuin.sokar.core.credential.Credential.ANY : purpose,
                                source));
                if (!flag(parameters, "dryRun")) {
                    // A dry run ANSWERS a refusal rather than raising it: being told what would
                    // be turned away is the whole reason it is asked. Raising here meant the one
                    // call that exists to report refusals was the one that could not.
                    org.fuin.sokar.app.CredentialDeclarations.checkPossible(declared);
                }
            } catch (org.fuin.sokar.core.credential.CredentialException ex) {
                throw new VarlinkException(INTERFACE + ".Failed",
                        Map.of("message", String.valueOf(ex.getMessage())));
            }
            final org.fuin.sokar.app.CredentialDeclarations declarations =
                    new org.fuin.sokar.app.CredentialDeclarations(context);
            final String fromFile = text(parameters, "fromFile");
            if (flag(parameters, "dryRun")) {
                // Nothing is written. Every refusal a real declaration gives, plus - for a key -
                // who the forge thinks we are, which catches a key that is for another account or
                // another repository at the moment somebody chooses it rather than at the first
                // fetch. Agent Frontend's idea, and the better moment by a mile.
                final org.fuin.sokar.app.CredentialDeclarations.Check would =
                        declarations.wouldDeclare(declared);
                // Only worth asking when the value is actually there; a key nothing holds cannot
                // be offered to a host anyway.
                final org.fuin.sokar.app.ForgeIdentity.Answer answer =
                        would.credential() == null
                                || would.outcome() != org.fuin.sokar.app.CredentialDeclarations
                                        .Outcome.READY
                        ? null
                        : org.fuin.sokar.app.ForgeIdentity.ask(context, declared.match(),
                                "declare", would.credential());
                // A host that turns the key away makes this NOT ready, whatever else is in
                // order: the record would be perfect and the first fetch would fail.
                final String outcome = answer != null && answer.refused()
                        ? org.fuin.sokar.app.CredentialDeclarations.Outcome.KEY_REFUSED.name()
                        : would.outcome().name();
                // What the CHECK said to run, when it said anything: for a machine with no vault
                // that is 'vault init', and recomputing it here sent somebody to 'vault put'
                // while the sentence beside it said there was nowhere to put anything.
                final String store = would.storeCommand().isEmpty()
                        ? org.fuin.sokar.app.CredentialDeclarations.storeCommandFor(
                                would.credential() == null ? declared : would.credential(),
                                fromFile)
                        : would.storeCommand();
                replies.last(Map.of("connection", would.credential() == null
                                ? Map.<String, Object>of()
                                : connectionAsMap(declarations, would.credential()),
                        "outcome", outcome,
                        "storeCommand",
                        org.fuin.sokar.app.CredentialDeclarations.argumentsOf(store),
                        // Answered the same way the real declaration answers it, or a wizard
                        // cannot tell from the check whether step four pipes a file or opens a
                        // terminal - which is what it asked the check for.
                        "storeStdin", store.contains("<") ? "the private key file" : "",
                        "identity", answer == null ? "" : answer.said(),
                        "detail", answer != null && answer.refused()
                                ? "that host turns this key away: " + answer.said()
                                : would.detail(),
                        "replaced", false,
                        "recorded", false));
                return;
            }
            // Declaring twice is not an error: a wizard run again has to land in the same place.
            // Whether it replaced one is answered, so an interface can say "updated".
            final boolean replaced = context.credentialRegistry().all().stream()
                    .anyMatch(existing -> existing.match().equals(declared.match())
                            && existing.purpose().equals(declared.purpose()));
            try {
                declarations.declare(declared);
            } catch (java.io.IOException ex) {
                throw new VarlinkException(INTERFACE + ".Failed",
                        Map.of("message", String.valueOf(ex.getMessage())));
            }
            final String store = org.fuin.sokar.app.CredentialDeclarations.storeCommandFor(
                    declared, fromFile);
            replies.last(Map.of("connection", connectionAsMap(declarations, declared),
                    "storeCommand", org.fuin.sokar.app.CredentialDeclarations.argumentsOf(store),
                    // Nothing to pipe when the machine reads its own disk.
                    "storeStdin", store.contains("<") ? "the private key file" : "",
                    "identity", "",
                    "detail", "",
                    "replaced", replaced,
                    "recorded", true));
        });

        server.method("CredentialForget", (parameters, replies) -> {
            final org.fuin.sokar.app.CredentialDeclarations declarations =
                    new org.fuin.sokar.app.CredentialDeclarations(context);
            final String left;
            try {
                left = declarations.forget(text(parameters, "match"));
            } catch (java.io.IOException ex) {
                throw new VarlinkException(INTERFACE + ".Failed",
                        Map.of("message", String.valueOf(ex.getMessage())));
            }
            // The secret is not removed: a key in somebody's own directory is theirs, and a vault
            // entry is removed by a person at the machine.
            replies.last(Map.of("forgotten", left != null, "leftBehind", left == null ? "" : left));
        });

        server.method("SshKeys", (parameters, replies) -> {
            // Never a value: a private key is opened far enough to say what it is and whether a
            // passphrase protects it, and is not copied, printed or returned. Deciding what is a
            // key means reading files, which is why it is answered here rather than left to a
            // client listing a home directory over ssh and guessing.
            replies.last(Map.of("keys",
                    new org.fuin.sokar.app.SshKeys(context.paths().xdg().home()).all().stream()
                            .map(key -> {
                                final Map<String, Object> row =
                                        new java.util.LinkedHashMap<>(key.asMap());
                                row.put("obstacle",
                                        key.obstacle() == null ? "" : key.obstacle());
                                return row;
                            })
                            .toList()));
        });

        server.method("CredentialCheck", (parameters, replies) -> {
            final String purpose = text(parameters, "purpose");
            final org.fuin.sokar.app.CredentialDeclarations declarations =
                    new org.fuin.sokar.app.CredentialDeclarations(context);
            final org.fuin.sokar.app.CredentialDeclarations.Check check = declarations.check(
                    address(parameters, "url"), purpose.isEmpty() ? "git" : purpose);
            replies.last(Map.of("outcome", check.outcome().name(),
                    "connection", check.credential() == null ? Map.<String, Object>of()
                            : connectionAsMap(declarations, check.credential()),
                    "storeCommand", check.storeArguments(),
                    "storeStdin", check.storeStdin(), "detail", check.detail()));
        });

        // What a person has to authorize before work can use it: a start refused for want of a grant, a grant the
        // broker found ended. Every interface sees it, so a second person can answer what the first was refused.
        // What is open now first, then what changes; "granted" when a grant lands. Streaming only.
        server.method("Authorizations", (parameters, replies) -> {
            if (!replies.streaming()) {
                throw new VarlinkException(INTERFACE + ".StreamRequired", Map.of("method", "Authorizations"));
            }
            Map<String, Map<String, String>> previous = new LinkedHashMap<>();
            try {
                while (replies.open()) {
                    final Map<String, Map<String, String>> now = new LinkedHashMap<>();
                    org.fuin.sokar.app.AuthorizationsNeeded.open(context)
                            .forEach(question -> now.put(question.get("credential"), question));
                    for (final Map.Entry<String, Map<String, String>> open : now.entrySet()) {
                        if (!open.getValue().equals(previous.get(open.getKey()))) {
                            replies.more(new LinkedHashMap<>(open.getValue()));
                        }
                    }
                    for (final Map.Entry<String, Map<String, String>> gone : previous.entrySet()) {
                        if (!now.containsKey(gone.getKey())) {
                            final Map<String, Object> granted = new LinkedHashMap<>(gone.getValue());
                            granted.put("state", "granted");
                            granted.put("at", java.time.Instant.now().toString());
                            replies.more(granted);
                        }
                    }
                    previous = now;
                    Thread.sleep(PROMPT_INTERVAL.toMillis());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        server.method("Authorize", (parameters, replies) -> {
            if (!replies.streaming()) {
                throw new VarlinkException(INTERFACE + ".StreamRequired", Map.of("method", "Authorize"));
            }
            final String name = text(parameters, "name");
            final org.fuin.sokar.app.VaultAuthorizeCommand.Flow grant;
            final org.fuin.sokar.app.VaultAuthorizeCommand.Shown started;
            try {
                grant = org.fuin.sokar.app.VaultAuthorizeCommand.flowFor(context, name);
                started = grant.start();
            } catch (IllegalArgumentException | org.fuin.sokar.vault.VaultException
                    | org.fuin.sokar.supervisor.TokenPurchase.Refused ex) {
                throw new VarlinkException(INTERFACE + ".Failed", Map.of("message", String.valueOf(ex.getMessage())));
            }
            replies.more(Map.of("state", "needed", "link", started.link(), "code", started.code(),
                    "port", started.port(), "expiresIn", started.expiresIn().toSeconds(), "detail", ""));
            try {
                final org.fuin.sokar.supervisor.DeviceGrant.Outcome outcome = grant.await();
                // The same decision the terminal makes: a token that never expires is kept as it is.
                final org.fuin.sokar.app.VaultAuthorizeCommand.Kept kept = "granted".equals(outcome.state())
                        ? org.fuin.sokar.app.VaultAuthorizeCommand.keepGranted(context, name, outcome)
                        : new org.fuin.sokar.app.VaultAuthorizeCommand.Kept(false, "");
                if ("granted".equals(outcome.state()) && !kept.kept()) {
                    replies.last(Map.of("state", "refused", "link", "", "code", "", "port", 0, "expiresIn", 0,
                            "detail", kept.detail()));
                    return;
                }
                replies.last(Map.of("state", outcome.state(), "link", "", "code", "", "port", 0, "expiresIn", 0,
                        "detail", kept.detail()));
            } catch (org.fuin.sokar.supervisor.TokenPurchase.Refused | org.fuin.sokar.vault.VaultException ex) {
                replies.last(Map.of("state", "failed", "link", "", "code", "", "port", 0, "expiresIn", 0,
                        "detail", String.valueOf(ex.getMessage())));
            } catch (InterruptedException ex) {
                // The daemon is stopping; the person's decision is not lost at the service, only this wait.
                Thread.currentThread().interrupt();
                replies.last(Map.of("state", "failed", "link", "", "code", "", "port", 0, "expiresIn", 0,
                        "detail", "the daemon stopped while waiting; authorize again"));
            }
        });

        // Destinations are files; these read and write the same files a person edits by hand.
        server.method("Destinations", (parameters, replies) ->
                replies.last(Map.of("destinations", destinations(context))));

        server.method("Destination", (parameters, replies) -> {
            final String name = text(parameters, "name");
            replies.last(Map.of("destination", destinations(context).stream()
                    .filter(row -> name.equals(row.get("name")) && Boolean.TRUE.equals(row.get("inForce")))
                    .findFirst()
                    .orElseThrow(() -> new VarlinkException(INTERFACE + ".NoSuchDestination", Map.of("name", name)))));
        });

        server.method("WriteDestination", (parameters, replies) -> {
            final org.fuin.sokar.app.Destination destination;
            try {
                final String name = text(parameters, "name");
                destination = new org.fuin.sokar.app.Destination(name,
                        text(parameters, "label").isEmpty() ? name : text(parameters, "label"),
                        address(parameters, "upstream"),
                        text(parameters, "authHeader").isEmpty() ? "Authorization" : text(parameters, "authHeader"),
                        text(parameters, "authPrefix"), empty(parameters, "authQuery"));
            } catch (IllegalArgumentException ex) {
                throw new VarlinkException(INTERFACE + ".DestinationRefused",
                        Map.of("message", String.valueOf(ex.getMessage())));
            }
            final java.nio.file.Path dataHome = context.paths().xdg().data();
            final boolean dryRun = flag(parameters, "dryRun");
            final java.nio.file.Path file = dryRun
                    ? dataHome.resolve("destinations").resolve(destination.name() + ".yaml")
                    : org.fuin.sokar.app.Destination.write(dataHome, destination);
            replies.last(Map.of("destination", destinationRow(destination, file, false, true), "written", !dryRun));
        });

        server.method("RemoveDestination", (parameters, replies) -> {
            final java.nio.file.Path removed = org.fuin.sokar.app.Destination.remove(context.paths().xdg().data(),
                    text(parameters, "name"));
            replies.last(Map.of("removed", removed != null, "file", removed == null ? "" : removed.toString()));
        });
    }
}
