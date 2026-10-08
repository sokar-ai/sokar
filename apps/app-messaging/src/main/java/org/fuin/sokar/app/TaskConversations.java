package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.jspecify.annotations.Nullable;

/**
 * Takes a task into its project's conversations when it starts, and out of them when it is removed.
 * <p>
 * For every transport a peer of the project reaches through the project's own conversation - a
 * {@code <scheme>:} address - the transport's {@code setup} is run (it keeps what exists) and then its
 * {@code enroll} for the task, <strong>before anything of the task exists</strong>: a task whose messages
 * could not reach anybody is refused as a credential nobody can reach is, not discovered when its first
 * message stalls.
 * <p>
 * <strong>An offline project's messages never leave the machine</strong> (decided 2026-09-29): what
 * {@code setup} says the conversation reaches must be this machine's loopback, or the start is refused. The
 * rule is Sokar's; what a transport reaches is the transport's to say.
 */
final class TaskConversations {

    private final TransportLifecycle lifecycle;

    /**
     * Constructor.
     *
     * @param context Where the vault, the state and the adapters are.
     */
    TaskConversations(SokarContext context) {
        this.lifecycle = new TransportLifecycle(context, context.paths().messaging().transportDirectory());
    }

    /**
     * Takes a task into its project's conversations.
     *
     * @param project The task's project.
     * @param container The task.
     * @return Why it cannot be, in a person's words, or {@code null} when it is in every one.
     */
    @Nullable String enroll(Project project, String container) {
        for (final String scheme : project.mail().conversations()) {
            final Map<String, Object> settings = settings(project, scheme);
            try {
                final boolean offline = project.securityClass() == SecurityClass.OFFLINE;
                final TransportLifecycle.Conversation conversation =
                        lifecycle.setup(scheme, project.name(), settings, offline);
                if (!conversation.admitted()) {
                    return notAdmitted(scheme, conversation);
                }
                if (project.securityClass() == SecurityClass.OFFLINE) {
                    final List<String> away = conversation.reaches().stream().filter(host -> !loopback(host)).toList();
                    if (!away.isEmpty()) {
                        return "an offline project's messages never leave this machine, and its " + scheme
                                + " conversation reaches " + String.join(", ", away)
                                + "; give it a conversation on this machine's loopback";
                    }
                }
                lifecycle.enroll(scheme, project.name(), container, settings, offline);
            } catch (TransportLifecycle.Refused ex) {
                return "the task cannot take part in the project's " + scheme + " conversation: " + ex.getMessage();
            }
        }
        return null;
    }

    /**
     * Takes a task out of its project's conversations. Never stops the removal: what cannot be done is said.
     *
     * @param project The task's project.
     * @param container The task.
     * @param err Where a failure is said.
     */
    void retire(Project project, String container, PrintWriter err) {
        for (final String scheme : project.mail().conversations()) {
            try {
                lifecycle.retire(scheme, project.name(), container, settings(project, scheme));
            } catch (TransportLifecycle.Refused ex) {
                // Its secrets stay in the vault, so the account can still be retired: nothing is lost that a
                // transport which answers again could not finish.
                err.println("sokar: " + container + " is removed, and its account in the " + scheme
                        + " conversation is not: " + ex.getMessage());
                err.flush();
            }
        }
    }

    /**
     * Takes a removed task out of its project's conversations, finding the project by the task's name.
     *
     * @param context Where the projects are.
     * @param container The task.
     */
    void retire(SokarContext context, String container) {
        retire(context, container, null);
    }

    /**
     * Takes a removed task out of its project's conversations.
     * <p>
     * <strong>By the project the task's own records name</strong>, when they still do. Found by the name alone, the
     * first project whose name the container began with was taken, so project {@code a} caught the tasks of
     * {@code a-b}; without a record, the longest such name is the likeliest.
     *
     * @param context Where the projects are.
     * @param container The task.
     * @param project The task's project as its records name it, or {@code null} when they are gone.
     */
    void retire(SokarContext context, String container, @Nullable String project) {
        new ProjectInventory(context).projects().stream()
                .filter(summary -> summary.file() != null)
                .filter(summary -> project != null ? summary.name().equals(project)
                        : container.startsWith("sokar-" + summary.name() + "-"))
                .max(java.util.Comparator.comparingInt(summary -> summary.name().length()))
                .ifPresent(summary -> {
                    try {
                        retire(GateSupport.project(java.nio.file.Path.of(summary.file())), container,
                                new PrintWriter(System.err, true));
                    } catch (RuntimeException ex) {
                        // A project file that cannot be read any more: its conversations cannot be named either.
                    }
                });
    }

    /**
     * Lets a person into a project's conversation, and returns what they are shown - once: nothing of it but
     * who joined as whom is kept.
     *
     * @param context Where the state is.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @return What the transport printed: {@code login} for an interface and {@code shown} for a terminal.
     * @throws TransportLifecycle.Refused If the project has no conversation, or the transport refused.
     */
    Map<?, ?> join(SokarContext context, Project project, String person, boolean reset)
            throws TransportLifecycle.Refused {
        final String scheme = scheme(project);
        if (!reset && members(context, project).containsKey(person)) {
            throw new TransportLifecycle.Refused("'" + person + "' has joined " + project.name()
                    + " already, as " + members(context, project).get(person) + "; --reset gives them a new password", 17);
        }
        final boolean offline = project.securityClass() == SecurityClass.OFFLINE;
        lifecycle.setup(scheme, project.name(), settings(project, scheme), offline);
        final Map<?, ?> said = lifecycle.join(scheme, project.name(), person, reset, settings(project, scheme), offline);
        final String user = said.get("login") instanceof Map<?, ?> login && login.get("user") != null
                ? String.valueOf(login.get("user")) : person;
        final Map<String, String> members = new java.util.LinkedHashMap<>(members(context, project));
        members.put(person, user);
        try {
            final java.nio.file.Path file = membersFile(context, project, scheme);
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, org.fuin.sokar.wire.Json.write(members),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            // The account exists and the person has their login; only the list of who joined misses them.
        }
        return said;
    }

    /**
     * Returns who has joined a project's conversation, by the name join was given.
     *
     * @param context Where the state is.
     * @param project The project.
     * @return Person to the account the transport made, empty when nobody joined or it has none.
     */
    Map<String, String> members(SokarContext context, Project project) {
        if (project.mail().conversations().isEmpty()) {
            return Map.of();
        }
        final java.nio.file.Path file = membersFile(context, project, scheme(project));
        if (!java.nio.file.Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (org.fuin.sokar.wire.Json.parse(java.nio.file.Files.readString(file,
                    java.nio.charset.StandardCharsets.UTF_8)) instanceof Map<?, ?> read) {
                final Map<String, String> members = new java.util.LinkedHashMap<>();
                read.forEach((who, user) -> members.put(String.valueOf(who), String.valueOf(user)));
                return members;
            }
        } catch (java.io.IOException | RuntimeException ex) {
            // Unreadable: nobody is listed; the accounts themselves are the transport's.
        }
        return Map.of();
    }

    /**
     * Returns the project's conversation as {@code setup} described it last.
     *
     * @param project The project.
     * @return The transport, the conversation and what it reaches, or {@code null} when it has none or it was
     *         never set up.
     */
    @Nullable Described describe(Project project) {
        if (project.mail().conversations().isEmpty()) {
            return null;
        }
        final String scheme = scheme(project);
        final TransportLifecycle.Conversation conversation = lifecycle.conversation(scheme, project.name());
        return new Described(scheme, conversation == null ? new TransportLifecycle.Conversation("", List.of(), "",
                false, "", "") : conversation, conversation != null);
    }

    /**
     * Sets a project's conversation up on this machine, or confirms it, without a task: so a machine being bound
     * to a project learns the name it goes by there, and whether it is let in yet.
     *
     * @param project The project.
     * @return What it is now.
     * @throws TransportLifecycle.Refused If the project has no conversation, or the transport refused.
     */
    Described setUp(Project project) throws TransportLifecycle.Refused {
        final String scheme = scheme(project);
        final TransportLifecycle.Conversation conversation = lifecycle.setup(scheme, project.name(),
                settings(project, scheme), project.securityClass() == SecurityClass.OFFLINE);
        return new Described(scheme, conversation, true);
    }

    /**
     * Says why a task cannot take part while this machine is not let into the conversation.
     *
     * @param scheme The transport.
     * @param conversation What setup said.
     * @return In a person's words.
     */
    static String notAdmitted(String scheme, TransportLifecycle.Conversation conversation) {
        return "this machine is not in the project's " + scheme + " conversation yet: a person there lets its account"
                + (conversation.address().isEmpty() ? "" : " " + conversation.address()) + " in"
                + (conversation.room().isEmpty() ? "" : " to " + conversation.room())
                + ", and the next task start takes it up";
    }

    /**
     * A project's conversation, as an interface is told of it.
     *
     * @param transport The transport it is on.
     * @param said What {@code setup} said of it last, empty before it ran.
     * @param setUp Whether it was set up on this machine.
     */
    record Described(String transport, TransportLifecycle.Conversation said, boolean setUp) {

        String conversation() {
            return said.conversation();
        }

        List<String> reaches() {
            return said.reaches();
        }
    }

    private static String scheme(Project project) throws IllegalStateException {
        if (project.mail().conversations().isEmpty()) {
            throw new IllegalStateException("'" + project.name() + "' has no conversation: no peer is reached"
                    + " through a transport's '<scheme>:' address");
        }
        return project.mail().conversations().iterator().next();
    }

    private static java.nio.file.Path membersFile(SokarContext context, Project project, String scheme) {
        return context.paths().xdg().state().resolve("transport").resolve(scheme).resolve("members")
                .resolve(project.name() + ".json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> settings(Project project, String scheme) {
        return project.mail().transports().get(scheme) instanceof Map<?, ?> said ? (Map<String, Object>) said
                : Map.of();
    }

    /**
     * Returns whether a host is this machine's loopback.
     *
     * @param host As the transport said it.
     * @return true for {@code 127.0.0.1}, {@code ::1}, {@code [::1]} and {@code localhost}, with or without a port.
     */
    static boolean loopback(String host) {
        final String bare = host.startsWith("[") ? host.substring(1, Math.max(1, host.indexOf(']')))
                : host.contains(":") && host.indexOf(':') == host.lastIndexOf(':') ? host.substring(0, host.indexOf(':'))
                : host;
        return "127.0.0.1".equals(bare) || "::1".equals(bare) || "localhost".equals(bare);
    }
}
