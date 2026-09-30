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
        if (described == null) {
            return null;
        }
        final boolean installed = context.paths().transportDirectory().find(described.transport()) != null;
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("transport", described.transport());
        row.put("conversation", described.conversation());
        row.put("reaches", described.reaches());
        row.put("ready", installed && described.setUp());
        row.put("detail", !installed ? "no '" + described.transport() + "' transport is installed on this machine"
                : !described.setUp() ? "set up when the project's first task starts, or when somebody joins" : "");
        row.put("loopbackOnly", project.securityClass() == SecurityClass.OFFLINE);
        return row;
    }
}
