package org.fuin.sokar.agent.impl.claude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;

/**
 * The answers a fresh container would otherwise be asked for.
 * <p>
 * Measured, not documented. A container has never been logged in, so the CLI runs its first-run
 * wizard: it asks for a theme, then offers a login menu, then asks whether the workspace is
 * trusted. The login menu belongs to that wizard rather than to any check of the credential -
 * with the wizard marked done, the stored token is used without question, phantom or not.
 * <p>
 * <strong>This is the agent's own state, not the provider's.</strong> It would look the same
 * whoever served the model, which is why it is kept apart from the credential file beside it.
 */
final class ClaudeFirstRun {

    /** Where the CLI keeps the answers to its first-run questions. */
    static final String FILE = "/home/agent/.claude.json";

    private ClaudeFirstRun() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the document that marks the first run as done.
     *
     * @param workspace Directory the agent works in.
     * @return File content.
     */
    static String document(String workspace) {

        final Map<String, Object> project = new LinkedHashMap<>();
        // Asked once per directory. In a container that directory is new every time, so without
        // this every task begins by asking whether the operator trusts their own project.
        project.put("hasTrustDialogAccepted", true);
        project.put("allowedTools", List.of());

        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("hasCompletedOnboarding", true);
        root.put("theme", "dark");
        root.put("projects", Map.of(workspace, project));
        return Json.write(root);
    }
}
