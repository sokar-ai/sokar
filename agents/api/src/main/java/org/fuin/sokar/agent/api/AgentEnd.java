package org.fuin.sokar.agent.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How an agent's run ended, as the agent reads its own last event.
 * <p>
 * The agent reads it, not Sokar: each agent ends its output its own way - one with a result line, another with an
 * end event whose last message carries the reason - and Sokar stays free of any agent's shape.
 *
 * @param finished Whether the prompt was answered to its end.
 * @param error Empty when finished; otherwise the text as the agent or its provider gave it.
 * @param source Whose the error is: {@code provider} or {@code agent}.
 * @param status The provider's HTTP status, when the provider refused.
 */
public record AgentEnd(boolean finished, String error, String source, @Nullable Integer status) {

    /** The error was the provider's. */
    public static final String PROVIDER = "provider";

    /** The error was the agent's own. */
    public static final String AGENT = "agent";

    /**
     * Returns it as it crosses the wire.
     *
     * @return The fields; the status only when there is one.
     */
    public Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("finished", finished);
        map.put("error", error);
        map.put("source", source);
        if (status != null) {
            map.put("status", status);
        }
        return map;
    }

    /**
     * Reads it as it crossed the wire.
     *
     * @param map The fields.
     * @return The end, or {@code null} when the map holds none.
     */
    public static @Nullable AgentEnd of(Map<?, ?> map) {
        if (!(map.get("finished") instanceof Boolean finished)) {
            return null;
        }
        return new AgentEnd(finished, map.get("error") instanceof String error ? error : "",
                map.get("source") instanceof String source ? source : AGENT,
                map.get("status") instanceof Number status ? status.intValue() : null);
    }
}
