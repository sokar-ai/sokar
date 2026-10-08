package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.vault.VaultEntry;

/**
 * Records whom a task acts as: for each credential it holds that a person granted, who granted it and when.
 * <p>
 * <strong>An audit fact, not a credential fact.</strong> With a grant a task acts as a named person,
 * unattended, and the record has to say so and outlive the task. Two places: the task's own record, which an
 * interface reads while the task exists, and one line per grant in the account's {@code grants.log}, which
 * is never removed with a task. Decided by the operator on 2026-09-30, until the machine log takes it over.
 */
final class GrantRecord {

    /** The account's log of tasks acting as a person, one JSON object per line, under the state directory. */
    static final String LOG = "grants.log";

    private GrantRecord() {
    }

    /**
     * Records the grants a task was started with.
     *
     * @param context Where the vault and the state are.
     * @param container The task.
     * @param project Its project.
     * @param credentials The credentials it holds beyond its agent's own.
     * @param err Where a failure to record is said; it never stops the task.
     */
    static void record(SokarContext context, String container, String project, Collection<String> credentials,
            PrintWriter err) {
        final Map<String, VaultEntry> grants = context.readableGrants().orElseGet(Map::of);
        final Map<String, Object> given = new LinkedHashMap<>();
        final StringBuilder lines = new StringBuilder();
        final String now = Instant.now().toString();
        for (final String name : credentials) {
            final VaultEntry grant = grants.get(name);
            if (grant == null) {
                continue;
            }
            final Map<String, String> who = new LinkedHashMap<>();
            who.put("grantedBy", grant.settings().getOrDefault("granted_by", ""));
            who.put("grantedAt", grant.settings().getOrDefault("granted_at", ""));
            given.put(name, who);
            final Map<String, Object> line = new LinkedHashMap<>();
            line.put("at", now);
            line.put("task", container);
            line.put("project", project);
            line.put("credential", name);
            line.putAll(who);
            lines.append(org.fuin.sokar.wire.Json.write(line)).append('\n');
        }
        if (given.isEmpty()) {
            return;
        }
        try {
            Files.writeString(context.paths().tasks().containerState(container).resolve(TaskInventory.GRANTS_FILE),
                    org.fuin.sokar.wire.Json.write(given), StandardCharsets.UTF_8);
            final Path log = context.paths().xdg().state().resolve(LOG);
            Files.createDirectories(log.getParent());
            Files.writeString(log, lines.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ex) {
            err.println("sokar: could not record whom this task acts as: " + ex.getMessage());
            err.flush();
        }
    }
}
