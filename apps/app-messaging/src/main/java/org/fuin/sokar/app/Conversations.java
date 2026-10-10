package org.fuin.sokar.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.jspecify.annotations.Nullable;

/**
 * What an interface may ask about a project's conversation - its room on a transport that keeps one - and the
 * one thing it may do there: let a person in.
 */
public final class Conversations {

    /**
     * Why a person could not be let in.
     */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final int code;

        private final @Nullable String exists;

        Refused(String message, int code, @Nullable String exists) {
            super(message);
            this.code = code;
            this.exists = exists;
        }

        /**
         * Returns the transport's exit code, or 0 when it never ran.
         *
         * @return The code.
         */
        public int code() {
            return code;
        }

        /**
         * Returns the account the person already has, when that is why.
         *
         * @return The account, or {@code null} for any other reason.
         */
        public @Nullable String exists() {
            return exists;
        }
    }

    private Conversations() {
    }

    /**
     * Lets a person into a project's conversation, and returns what they are shown - once.
     *
     * @param context Where the vault and the state are.
     * @param project The project.
     * @param person Whom the account is for.
     * @param reset Whether to give an existing one a new password.
     * @return What the transport printed: {@code login} and {@code shown}.
     * @throws Refused If the person has joined already and {@code reset} was not asked, or the transport refused.
     */
    public static Map<?, ?> join(SokarContext context, Project project, String person, boolean reset)
            throws Refused {
        final TaskConversations conversations = new TaskConversations(context);
        final String existing = conversations.members(context, project).get(person);
        if (existing != null && !reset) {
            throw new Refused("'" + person + "' has joined " + project.name() + " already, as " + existing, 0, existing);
        }
        try {
            return conversations.join(context, project, person, reset);
        } catch (TransportLifecycle.Refused ex) {
            throw new Refused(String.valueOf(ex.getMessage()), ex.code(), null);
        }
    }

    /**
     * Returns who has joined a project's conversation.
     *
     * @param context Where the state is.
     * @param project The project.
     * @return Person to the account the transport made.
     */
    public static Map<String, String> members(SokarContext context, Project project) {
        return new TaskConversations(context).members(context, project);
    }

    /**
     * Returns a project's conversation as a {@code Project} row carries it.
     *
     * @param context Where the state and the adapters are.
     * @param project The project.
     * @return The row's {@code messages}, or {@code null} for a project with no conversation.
     */
    public static @Nullable Map<String, Object> row(SokarContext context, Project project) {
        final TaskConversations.Described described = new TaskConversations(context).describe(project);
        return described == null ? null : row(context, project, described);
    }

    /**
     * Sets a project's conversation up on this machine, or confirms it, without starting a task - so a machine
     * being bound learns the name it goes by there and whether it is let in yet.
     *
     * @param context Where the vault, the state and the adapters are.
     * @param project The project.
     * @return Its row, as {@link #row(SokarContext, Project)} gives it.
     * @throws Refused If the project has no conversation, or the transport refused.
     */
    public static Map<String, Object> setUp(SokarContext context, Project project) throws Refused {
        try {
            return row(context, project, new TaskConversations(context).setUp(project));
        } catch (TransportLifecycle.Refused ex) {
            throw new Refused(String.valueOf(ex.getMessage()), ex.code(), null);
        }
    }

    private static Map<String, Object> row(SokarContext context, Project project,
            TaskConversations.Described described) {
        final boolean installed = context.paths().messaging().transportDirectory().find(described.transport()) != null;
        final TransportLifecycle.Conversation said = described.said();
        final boolean admitted = described.setUp() && said.admitted();
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("transport", described.transport());
        row.put("conversation", described.conversation());
        row.put("reaches", described.reaches());
        row.put("ready", installed && admitted);
        row.put("detail", !installed ? "no '" + described.transport() + "' transport is installed on this machine"
                : !described.setUp() ? "set up when the project's first task starts, or when somebody joins"
                : !admitted ? TaskConversations.notAdmitted(described.transport(), said) : "");
        row.put("loopbackOnly", project.securityClass() == SecurityClass.OFFLINE);
        row.put("machine", said.machine());
        row.put("admitted", admitted);
        row.put("address", said.address());
        row.put("room", said.room());
        row.put("waits", vaultWait(context));
        return row;
    }

    /** What a conversation waits for while the vault is locked. */
    static final String VAULT_LOCKED = "the vault is locked, so no conversation can be read or written: 'sokar vault"
            + " unlock' lets them go on";

    /**
     * Says why every conversation waits now: a locked vault holds every project's and task's account.
     * <p>
     * A vault unlocked for a time locked itself, and every message stopped without a word - none was fetched, an
     * answer was deferred, nothing in the journal (2026-10-04).
     *
     * @param context The machine.
     * @return {@link #VAULT_LOCKED}, or "" when the vault is open or there is none.
     */
    public static String vaultWait(SokarContext context) {
        try {
            return java.nio.file.Files.isRegularFile(context.paths().vault().vaultFile()) && context.opener().isEmpty()
                    ? VAULT_LOCKED : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }
}
