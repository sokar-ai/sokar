package org.fuin.sokar.clearance;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What was decided about one blocked destination, and by whom.
 * <p>
 * This is the audit line. It is written whether or not anybody answered, because a question nobody
 * answered is the case an operator most needs to find afterwards: the task went on running with
 * that destination blocked, and nothing else records why.
 *
 * @param at When it was decided.
 * @param project Project the task belongs to.
 * @param task Task name.
 * @param key Key the destination is decided under.
 * @param destination Host or address the task asked for.
 * @param port Port, or {@code 0} when there is none.
 * @param protocol Protocol name.
 * @param shown What the operator was shown, which may carry a resolved name.
 * @param verdict What was decided.
 * @param source Where the answer came from: {@link #PROMPT} or {@link #CLIENT}.
 */
public record Decision(Instant at, String project, String task, String key, String destination,
        int port, String protocol, String shown, Verdict verdict, String source) {

    /** The answer came from the prompt Sokar raised on this machine. */
    public static final String PROMPT = "prompt";

    /** The answer came from a client over the clearance socket. */
    public static final String CLIENT = "client";

    /**
     * Returns this decision as the event a subscriber receives.
     * <p>
     * The same field names the prompt event uses, plus {@code verdict}: an interface that showed
     * the question has to recognise the answer as the same destination, and the daemon rebuilds
     * the key from {@code protocol}, {@code destination} and {@code port}.
     * <p>
     * The task is not among them. One socket is one task, and {@code task} is the field the daemon
     * fills with the container name so an answer can be routed back - a field that meant the
     * operator's name for the task on one socket and the container's name on the other would be
     * worse than a field that is only ever the second.
     *
     * @return Event fields, in a fixed order.
     */
    public Map<String, Object> event() {
        final Map<String, Object> event = new LinkedHashMap<>();
        event.put("key", key);
        event.put("destination", destination);
        event.put("address", destination);
        event.put("protocol", protocol);
        event.put("port", port);
        event.put("shown", shown);
        event.put("project", project);
        event.put("at", at.toString());
        event.put("verdict", verdict.name().toLowerCase(java.util.Locale.ROOT));
        event.put("source", source);
        return event;
    }
}
