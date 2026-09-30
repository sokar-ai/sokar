package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultFile;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Runs a transport's lifecycle - {@code setup}, {@code enroll}, {@code retire}, {@code join} - for a transport
 * that keeps a conversation of its own, and keeps what it hands back.
 * <p>
 * <strong>Sokar knows no transport here.</strong> What a room is, how an account is made, which homeserver
 * runs where: all the transport's, behind these four verbs (decided by the operator on 2026-09-30). Sokar
 * hands each verb the project's settings for it as JSON on stdin, exactly as {@code mail.transports.<scheme>}
 * says them, and the secrets it kept as environment variables; it keeps the {@code secrets} and
 * {@code account} a verb prints in the account's vault, opaque, and hands them back as they were printed.
 * <p>
 * <strong>Three scopes, three entries, none ever in a task:</strong> the account's
 * ({@code transport/<scheme>/account}, only for the lifecycle verbs), a project's
 * ({@code transport/<scheme>/project/<p>}, what {@code poll} reads), and a task's
 * ({@code transport/<scheme>/task/<t>}, what {@code send}, {@code read} and {@code receipt} read).
 */
final class TransportLifecycle {

    /**
     * What a transport could not do, said in its own words and with its exit code.
     */
    static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final int code;

        Refused(String message, int code) {
            super(message);
            this.code = code;
        }

        /**
         * Returns the transport's exit code: 75 temporary, 77 refused, 78 configuration missing.
         *
         * @return The code, or 0 when it never ran.
         */
        int code() {
            return code;
        }
    }

    /**
     * What {@code setup} said of a project's conversation.
     *
     * @param conversation What a peer address {@code <scheme>:} stands for, handed to {@code send} as
     *        {@code --to}.
     * @param reaches The hosts the project's messages reach through this transport.
     */
    record Conversation(String conversation, List<String> reaches) {
    }

    private final SokarContext context;

    private final TransportDirectory transports;

    /**
     * Constructor.
     *
     * @param context Where the vault and the state are, and how commands run.
     * @param transports Where the adapters are.
     */
    TransportLifecycle(SokarContext context, TransportDirectory transports) {
        this.context = context;
        this.transports = transports;
    }

    /**
     * Sets up a project's conversation on a transport, or confirms it: run every time, it keeps what exists.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param settings What the project says to this transport, or an empty map.
     * @return What the conversation is and what it reaches.
     * @throws Refused If the transport refused, or its secrets cannot be kept.
     */
    Conversation setup(String scheme, String project, Map<String, Object> settings) throws Refused {
        final Map<?, ?> said = run(scheme, settings, secrets(scheme, "account"), "setup", "--project", project);
        if (said.get("account") instanceof Map<?, ?> account && !account.isEmpty()) {
            keep(scheme, "account", account);
        }
        if (said.get("secrets") instanceof Map<?, ?> secrets && !secrets.isEmpty()) {
            keep(scheme, "project/" + project, secrets);
        }
        final String conversation = said.get("conversation") instanceof String id ? id : "";
        final List<String> reaches = said.get("reaches") instanceof List<?> hosts
                ? hosts.stream().map(String::valueOf).toList() : List.of();
        final Conversation found = new Conversation(conversation, reaches);
        remember(scheme, project, found);
        return found;
    }

    /**
     * Enrolls a task into its project's conversation, or confirms it.
     *
     * @param scheme The transport.
     * @param project The task's project.
     * @param task The task's container.
     * @param settings What the project says to this transport.
     * @throws Refused If the transport refused, or its secrets cannot be kept.
     */
    void enroll(String scheme, String project, String task, Map<String, Object> settings) throws Refused {
        final Map<String, String> given = new LinkedHashMap<>(secrets(scheme, "account"));
        given.putAll(secrets(scheme, "project/" + project));
        final Map<?, ?> said = run(scheme, settings, given, "enroll", "--project", project, "--task", task);
        if (said.get("secrets") instanceof Map<?, ?> secrets && !secrets.isEmpty()) {
            keep(scheme, "task/" + task, secrets);
        }
    }

    /**
     * Retires a task from its project's conversation and forgets its secrets. A task the transport holds
     * nothing for is left as it is.
     *
     * @param scheme The transport.
     * @param project The task's project.
     * @param task The task's container.
     * @param settings What the project says to this transport.
     * @throws Refused If the transport refused; its secrets are then kept, so it can be retried.
     */
    void retire(String scheme, String project, String task, Map<String, Object> settings) throws Refused {
        final Map<String, String> own = secrets(scheme, "task/" + task);
        if (own.isEmpty()) {
            return;
        }
        final Map<String, String> given = new LinkedHashMap<>(secrets(scheme, "account"));
        given.putAll(secrets(scheme, "project/" + project));
        given.putAll(own);
        run(scheme, settings, given, "retire", "--project", project, "--task", task);
        forget(scheme, "task/" + task);
    }

    /**
     * Lets a person into a project's conversation, and returns what they are to be shown, once.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @param settings What the project says to this transport.
     * @return What the transport printed: {@code login} and {@code shown}. Never kept.
     * @throws Refused If the transport refused.
     */
    Map<?, ?> join(String scheme, String project, String person, boolean reset, Map<String, Object> settings)
            throws Refused {
        final Map<String, String> given = new LinkedHashMap<>(secrets(scheme, "account"));
        given.putAll(secrets(scheme, "project/" + project));
        return reset ? run(scheme, settings, given, "join", "--project", project, "--person", person, "--reset")
                : run(scheme, settings, given, "join", "--project", project, "--person", person);
    }

    /**
     * Returns what {@code setup} said last of a project's conversation.
     *
     * @param scheme The transport.
     * @param project The project.
     * @return It, or {@code null} when it was never set up.
     */
    @Nullable Conversation conversation(String scheme, String project) {
        final Path file = state(scheme, project);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> read) {
                return new Conversation(read.get("conversation") instanceof String id ? id : "",
                        read.get("reaches") instanceof List<?> hosts ? hosts.stream().map(String::valueOf).toList()
                                : List.of());
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable: set up again.
        }
        return null;
    }

    /**
     * Returns the secrets kept for a scope.
     *
     * @param scheme The transport.
     * @param scope {@code account}, {@code project/<p>} or {@code task/<t>}.
     * @return The variables, empty when nothing is kept or the vault is locked.
     */
    Map<String, String> secrets(String scheme, String scope) {
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty() || !context.vault().exists()) {
            return Map.of();
        }
        final VaultEntry entry;
        try {
            entry = context.vault().read(opener.get()).get(entry(scheme, scope));
        } catch (org.fuin.sokar.vault.VaultException ex) {
            return Map.of();
        }
        if (entry == null) {
            return Map.of();
        }
        try {
            if (Json.parse(entry.value()) instanceof Map<?, ?> read) {
                final Map<String, String> secrets = new LinkedHashMap<>();
                read.forEach((name, value) -> secrets.put(String.valueOf(name), String.valueOf(value)));
                return secrets;
            }
        } catch (RuntimeException ex) {
            // A damaged entry holds nothing usable; the verb that made it is run again.
        }
        return Map.of();
    }

    private Map<?, ?> run(String scheme, Map<String, Object> settings, Map<String, String> secrets, String... verb)
            throws Refused {
        final Path adapter = transports.find(scheme);
        if (adapter == null) {
            throw new Refused("no transport for '" + scheme + "' is installed", 0);
        }
        final List<String> arguments = new java.util.ArrayList<>();
        arguments.add(adapter.toString());
        arguments.addAll(List.of(verb));
        final CommandResult result = context.runner().run(Command.of(arguments)
                .withInput(Json.write(settings)).withEnvironment(secrets));
        if (!result.successful()) {
            final String said = result.standardError().strip();
            throw new Refused("the " + scheme + " transport could not " + verb[0] + " (exit " + result.exitCode()
                    + ")" + (said.isEmpty() ? "" : ": " + said), result.exitCode());
        }
        try {
            if (Json.parse(result.standardOutput()) instanceof Map<?, ?> said) {
                return said;
            }
        } catch (RuntimeException ex) {
            // Falls through to the refusal below; what it printed is never repeated - it may hold a secret.
        }
        throw new Refused("the " + scheme + " transport answered " + verb[0] + " with something that is not a"
                + " JSON object", 76);
    }

    private void keep(String scheme, String scope, Map<?, ?> secrets) throws Refused {
        final Map<String, String> kept = new LinkedHashMap<>();
        secrets.forEach((name, value) -> kept.put(String.valueOf(name), String.valueOf(value)));
        final VaultEntry entry = new VaultEntry(Json.write(kept), "transport-secrets", Map.of("transport", scheme));
        update(scheme, all -> all.put(entry(scheme, scope), entry));
    }

    private void forget(String scheme, String scope) throws Refused {
        update(scheme, all -> all.remove(entry(scheme, scope)));
    }

    private void update(String scheme, java.util.function.Consumer<Map<String, VaultEntry>> change) throws Refused {
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty()) {
            throw new Refused("the vault is locked, and what the " + scheme + " transport handed back goes into it;"
                    + " 'sokar vault unlock' at the machine", 0);
        }
        try {
            context.vault().update(opener.get(), all -> {
                change.accept(all);
                return all;
            });
        } catch (org.fuin.sokar.vault.VaultException ex) {
            throw new Refused("the vault cannot be written: " + ex.getMessage(), 0);
        }
    }

    private void remember(String scheme, String project, Conversation conversation) throws Refused {
        final Map<String, Object> written = new LinkedHashMap<>();
        written.put("conversation", conversation.conversation());
        written.put("reaches", conversation.reaches());
        try {
            final Path file = state(scheme, project);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(written), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Refused("the conversation of " + project + " cannot be recorded: " + ex.getMessage(), 0);
        }
    }

    private Path state(String scheme, String project) {
        return context.paths().xdg().state().resolve("transport").resolve(scheme).resolve(project + ".json");
    }

    private static String entry(String scheme, String scope) {
        return TaskSecrets.TRANSPORT_PREFIX + scheme + "/" + scope;
    }
}
