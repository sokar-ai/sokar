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
     * @param machine The name this machine goes by there, as the transport kept it, or "".
     * @param admitted Whether this machine is in the conversation. False on a shared server until a person there
     *        has let this machine's account in; absent from what the transport said means true.
     * @param address This machine's account there, as the server names it, or "".
     * @param room The conversation as a person names it - an alias or the id the settings give - or "".
     */
    record Conversation(String conversation, List<String> reaches, String machine, boolean admitted, String address,
            String room) {

        /**
         * Constructor for a conversation this machine is in, as a transport without a shared server says it.
         *
         * @param conversation What a peer address stands for.
         * @param reaches The hosts the project's messages reach.
         */
        Conversation(String conversation, List<String> reaches) {
            this(conversation, reaches, "", true, "", "");
        }

        private static Conversation of(Map<?, ?> said) {
            return new Conversation(text(said.get("conversation")),
                    said.get("reaches") instanceof List<?> hosts ? hosts.stream().map(String::valueOf).toList()
                            : List.of(),
                    text(said.get("machine")), !Boolean.FALSE.equals(said.get("admitted")), text(said.get("address")),
                    text(said.get("room")));
        }

        private static String text(@org.jspecify.annotations.Nullable Object value) {
            return value instanceof String said ? said : "";
        }
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
        return setup(scheme, project, settings, false);
    }

    /**
     * Sets up a project's conversation, telling the transport when it may reach nothing beyond this machine.
     * <p>
     * <strong>{@code --loopback-only}</strong> for an offline project: the transport refuses (78) before it
     * contacts anything but this machine's loopback. Checking {@code reaches} afterwards alone came too late -
     * the transport had already reached out (found by Agent Matrix, 2026-09-30). Both hold: the transport is
     * told, and what it says it reaches is still checked.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @return What the conversation is and what it reaches.
     * @throws Refused If the transport refused, or its secrets cannot be kept.
     */
    Conversation setup(String scheme, String project, Map<String, Object> settings, boolean loopbackOnly)
            throws Refused {
        writable(scheme);
        // The account's and the project's, as they were printed: so it keeps what it made - the project's relay
        // - rather than making it again (asked by Agent Matrix, 2026-09-30).
        final Map<String, String> given = new LinkedHashMap<>(secrets(scheme, "account"));
        given.putAll(secrets(scheme, "project/" + project));
        final Map<?, ?> said = run(scheme, settings, given, loopbackOnly, "setup", "--project", project);
        if (said.get("account") instanceof Map<?, ?> account && !account.isEmpty()) {
            keep(scheme, "account", account);
        }
        if (said.get("secrets") instanceof Map<?, ?> secrets && !secrets.isEmpty()) {
            keep(scheme, "project/" + project, secrets);
        }
        // Kept even when this machine is not admitted yet: the account the transport registered is in the
        // vault by now, and the name a person needs to let it in is in what is remembered.
        final Conversation found = Conversation.of(said);
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
        enroll(scheme, project, task, settings, false);
    }

    /**
     * Enrolls a task, telling the transport when it may reach nothing beyond this machine.
     *
     * @param scheme The transport.
     * @param project The task's project.
     * @param task The task's container.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @throws Refused If the transport refused, or its secrets cannot be kept.
     */
    void enroll(String scheme, String project, String task, Map<String, Object> settings, boolean loopbackOnly)
            throws Refused {
        writable(scheme);
        final Map<String, String> given = new LinkedHashMap<>(secrets(scheme, "account"));
        given.putAll(secrets(scheme, "project/" + project));
        final Map<?, ?> said = run(scheme, settings, given, loopbackOnly, "enroll", "--project", project, "--task",
                task);
        if (said.get("secrets") instanceof Map<?, ?> secrets && !secrets.isEmpty()) {
            keep(scheme, "task/" + task, secrets);
        }
        if (said.get("address") instanceof String address && !address.isBlank()) {
            // How the task is named in the conversation - not a secret - so what is sent to it can be asked
            // about: 'receipt --by' takes it.
            final Map<String, String> addresses = new LinkedHashMap<>(addresses(scheme, project));
            addresses.put(task, address);
            try {
                final Path file = addressesFile(scheme, project);
                Files.createDirectories(file.getParent());
                Files.writeString(file, Json.write(addresses), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                // The task takes part all the same; only whether it read something cannot be asked.
            }
        }
    }

    /**
     * Returns how each enrolled task of a project is named in its conversation, as {@code enroll} said.
     *
     * @param scheme The transport.
     * @param project The project.
     * @return Task container to its address; empty when the transport named none.
     */
    Map<String, String> addresses(String scheme, String project) {
        return names(addressesFile(scheme, project));
    }

    /**
     * Returns who has joined a project's conversation, as {@code join} named their account.
     *
     * @param scheme The transport.
     * @param project The project.
     * @return Person to their account.
     */
    Map<String, String> people(String scheme, String project) {
        return names(context.paths().xdg().state().resolve("transport").resolve(scheme).resolve("members")
                .resolve(project + ".json"));
    }

    private Path addressesFile(String scheme, String project) {
        return context.paths().xdg().state().resolve("transport").resolve(scheme).resolve("addresses")
                .resolve(project + ".json");
    }

    private static Map<String, String> names(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> read) {
                final Map<String, String> names = new LinkedHashMap<>();
                read.forEach((key, value) -> names.put(String.valueOf(key), String.valueOf(value)));
                return names;
            }
        } catch (IOException | RuntimeException ex) {
            // Unreadable: nobody is named.
        }
        return Map.of();
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
        run(scheme, settings, given, false, "retire", "--project", project, "--task", task);
        forget(scheme, "task/" + task);
    }

    /**
     * Clears a project's conversation, or with no project the account's, and forgets what Sokar kept for it.
     * <p>
     * The transport's {@code clear} runs where its {@code describe} offers it; Sokar's own entries go either way:
     * kept, a task started again after the transport had cleared ran no {@code setup} and stayed outside a
     * conversation that was gone. A project's are its own and its tasks'; the account's is cleared only with no
     * project, after the last.
     *
     * @param scheme The transport.
     * @param project The project, or {@code null} for the account.
     * @param settings What the project says to this transport; empty for the account.
     * @return What the transport said it removed.
     * @throws Refused If the transport refused; what Sokar kept is then kept too, so it can be retried.
     */
    List<String> clear(String scheme, @Nullable String project, Map<String, Object> settings) throws Refused {
        final List<String> removed = new java.util.ArrayList<>();
        final Path adapter = transports.find(scheme);
        if (adapter != null && TransportDescription.of(context.runner(), adapter).offers("clear")) {
            final Map<String, String> given = new LinkedHashMap<>(secrets(scheme, "account"));
            if (project != null) {
                given.putAll(secrets(scheme, "project/" + project));
            }
            final Map<?, ?> said = project == null ? run(scheme, settings, given, false, "clear")
                    : run(scheme, settings, given, false, "clear", "--project", project);
            if (said.get("removed") instanceof List<?> listed) {
                listed.forEach(each -> removed.add(String.valueOf(each)));
            }
        }
        if (!context.vault().exists() || context.opener().isEmpty()) {
            return removed;
        }
        if (project == null) {
            forget(scheme, "account");
            return removed;
        }
        final String tasks = TaskSecrets.TRANSPORT_PREFIX + scheme + "/task/sokar-" + project + "-";
        update(scheme, all -> {
            all.remove(entry(scheme, "project/" + project));
            all.keySet().removeIf(name -> name.startsWith(tasks));
        });
        try {
            Files.deleteIfExists(state(scheme, project));
            Files.deleteIfExists(addressesFile(scheme, project));
            Files.deleteIfExists(context.paths().xdg().state().resolve("transport").resolve(scheme)
                    .resolve("members").resolve(project + ".json"));
        } catch (IOException ex) {
            throw new Refused("what this machine kept of the " + scheme + " conversation of " + project
                    + " could not be removed: " + ex.getMessage(), 0);
        }
        return removed;
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
        return join(scheme, project, person, reset, settings, false);
    }

    /**
     * Lets a person in, telling the transport when it may reach nothing beyond this machine.
     *
     * @param scheme The transport.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @param settings What the project says to this transport.
     * @param loopbackOnly Whether the project is offline.
     * @return What the transport printed.
     * @throws Refused If the transport refused.
     */
    Map<?, ?> join(String scheme, String project, String person, boolean reset, Map<String, Object> settings,
            boolean loopbackOnly) throws Refused {
        final Map<String, String> given = new LinkedHashMap<>(secrets(scheme, "account"));
        given.putAll(secrets(scheme, "project/" + project));
        return reset ? run(scheme, settings, given, loopbackOnly, "join", "--project", project, "--person", person,
                "--reset") : run(scheme, settings, given, loopbackOnly, "join", "--project", project, "--person", person);
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
                return Conversation.of(read);
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
            // Locked: nothing read before may be used any longer.
            VaultCache.drop();
            return Map.of();
        }
        final VaultEntry entry;
        try {
            entry = VaultCache.read(context.vault(), opener.get()).get(entry(scheme, scope));
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

    /**
     * What a transport says of the settings a project gives it.
     *
     * @param refused What it would refuse wherever it runs.
     * @param warnings What only this machine lacks, or what is allowed but not advised.
     */
    record SettingsCheck(List<String> refused, List<String> warnings) {
    }

    /**
     * Asks a transport what it makes of the settings a project gives it, without the network, the vault
     * or any secret - so a draft is checked before it is committed rather than refused at the first task
     * start. Only a transport whose {@code describe} lists {@code settings} is asked.
     *
     * @param scheme The transport.
     * @param settings What the project says to it.
     * @return What it said, or {@code null} when it is not installed here or does not offer the check.
     * @throws Refused If it offers the check and could not answer it.
     */
    @org.jspecify.annotations.Nullable
    SettingsCheck settings(String scheme, Map<String, Object> settings) throws Refused {
        final Path adapter = transports.find(scheme);
        if (adapter == null || !TransportDescription.of(context.runner(), adapter).offers("settings")) {
            return null;
        }
        final Map<?, ?> said = run(scheme, settings, Map.of(), false, "settings");
        return new SettingsCheck(strings(said.get("refused")), strings(said.get("warnings")));
    }

    private static List<String> strings(@org.jspecify.annotations.Nullable Object listed) {
        return listed instanceof List<?> each ? each.stream().map(String::valueOf).toList() : List.of();
    }

    /**
     * Refuses before a verb runs when what it hands back could not be kept: a transport that made an
     * account whose token is then dropped has an account nobody can use or remove (found by Agent Matrix,
     * 2026-09-30, with a locked vault: the homeserver's administrator registered and lost).
     *
     * @param scheme The transport.
     * @throws Refused If the vault is locked or does not exist.
     */
    private void writable(String scheme) throws Refused {
        if (!context.vault().exists()) {
            throw new Refused("there is no vault, and what the " + scheme + " transport hands back has to be kept in"
                    + " one; 'sokar vault init' at the machine", 0);
        }
        if (context.opener().isEmpty()) {
            throw new Refused("the vault is locked, and what the " + scheme + " transport hands back has to be kept"
                    + " in it; 'sokar vault unlock' at the machine", 0);
        }
    }

    private Map<?, ?> run(String scheme, Map<String, Object> settings, Map<String, String> secrets,
            boolean loopbackOnly, String... verb) throws Refused {
        final Path adapter = transports.find(scheme);
        if (adapter == null) {
            throw new Refused("no transport for '" + scheme + "' is installed", 0);
        }
        final List<String> arguments = new java.util.ArrayList<>();
        arguments.add(adapter.toString());
        arguments.addAll(List.of(verb));
        if (loopbackOnly) {
            arguments.add("--loopback-only");
        }
        // This machine's name, to a transport that takes it: two machines on one homeserver run tasks of the
        // same name, and without it the second one's enroll would take the first one's account over. The
        // transport keeps the name it first used, so a machine renamed later keeps its accounts.
        if (!"settings".equals(verb[0]) && TransportDescription.of(context.runner(), adapter).takes("machine")) {
            arguments.add("--machine");
            arguments.add(DeployKeys.hostName());
        }
        final CommandResult result = context.runner().run(HelperEnvironment.chosen(Command.of(arguments)
                .withInput(Json.write(settings)).withEnvironment(secrets)));
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
        written.put("machine", conversation.machine());
        written.put("admitted", conversation.admitted());
        written.put("address", conversation.address());
        written.put("room", conversation.room());
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
