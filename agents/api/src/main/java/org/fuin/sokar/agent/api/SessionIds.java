package org.fuin.sokar.agent.api;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where this agent names the session it is running - declared by the agent, because the record, the field
 * and the file shape are facts about one agent and nothing outside {@code agents/} may know them.
 * <p>
 * <strong>Read from what the host already has.</strong> A run's own machine-readable output for an
 * unattended run; the agent's own session files, inside the container, for an attached one. Nothing new
 * crosses out of the container for it, and the agent is given no way to send it.
 * <p>
 * <strong>A session id is an identifier, not a credential.</strong> It names a transcript on this machine
 * and authorizes nothing, so passing it on a command line - as {@code resume_flag} needs - is allowed.
 *
 * @param record The record an unattended run names its session in: top-level fields and the values they must
 *        have, or empty when the agent's machine-readable output does not name it.
 * @param key The field of that record holding the id, or {@code null} with an empty {@code record}.
 * @param directory Where an attached agent keeps its session files, relative to its home directory, or
 *        {@code null} when it keeps none Sokar can read.
 * @param suffix What a session file's name ends in; the rest of the name is the id, and the newest such
 *        file names the session. {@code null} with a {@code null} directory.
 */
public record SessionIds(Map<String, String> record, @Nullable String key, @Nullable String directory,
        @Nullable String suffix) {

    /**
     * Constructor with validation.
     *
     * @param record The record's fields.
     * @param key The field holding the id.
     * @param directory The session files' directory.
     * @param suffix Their names' ending.
     */
    public SessionIds {
        record = Map.copyOf(record);
        if (record.isEmpty() != (key == null || key.isBlank())) {
            throw new AgentException("A session id in a run's records needs both the record and its key");
        }
        if ((directory == null) != (suffix == null)) {
            throw new AgentException("A session id in session files needs both the directory and the suffix");
        }
        if (record.isEmpty() && directory == null) {
            throw new AgentException("A session id declaration that names neither a record nor files is"
                    + " written by leaving it out");
        }
        if (directory != null && (directory.isBlank() || directory.startsWith("/") || directory.contains("..")
                || !directory.matches("[A-Za-z0-9._/-]+"))) {
            // It reaches a command run in the container, so only what a path under the home needs.
            throw new AgentException("A session directory is a plain path under the agent's home, not '"
                    + directory + "'");
        }
        if (suffix != null && (suffix.isBlank() || !suffix.matches("[A-Za-z0-9._-]+"))) {
            throw new AgentException("A session file suffix is a plain file name ending, not '" + suffix + "'");
        }
    }

    /**
     * Picks the session id out of an unattended run's records.
     *
     * @param records The run's machine-readable output, one parsed record per line, in order.
     * @return The id the first matching record names, or {@code null} when none does or none is declared.
     */
    public @Nullable String of(java.util.List<?> records) {
        if (record.isEmpty() || key == null) {
            return null;
        }
        for (final Object each : records) {
            if (each instanceof Map<?, ?> fields && matches(fields) && fields.get(key) instanceof String id
                    && isId(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * Whether a text can be a session id: what reaches a command line has to be one plain word.
     *
     * @param id The text.
     * @return true for letters, digits, dots, dashes and underscores, at most 200 of them
     */
    public static boolean isId(String id) {
        return id.length() <= 200 && id.matches("[A-Za-z0-9._-]+");
    }

    private boolean matches(Map<?, ?> fields) {
        for (final Map.Entry<String, String> wanted : record.entrySet()) {
            if (!wanted.getValue().equals(fields.get(wanted.getKey()))) {
                return false;
            }
        }
        return true;
    }

}
