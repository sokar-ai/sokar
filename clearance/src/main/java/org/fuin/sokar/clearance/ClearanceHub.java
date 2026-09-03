package org.fuin.sokar.clearance;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Decides what happens to each blocked connection, once per destination.
 * <p>
 * <strong>Deduplication is not an optimisation here, it is what makes the feature usable.</strong>
 * A blocked connection is retried: an agent's HTTP client, a package manager or a build tool will
 * produce hundreds of identical drop events a second. Asking once per event would bury the desktop
 * and make the answer meaningless.
 * <p>
 * A destination that has been asked about is never asked about again for the life of the hub, in
 * either direction. Re-asking after a deny would let an agent extract an allow by simply retrying
 * until the operator's attention slipped.
 */
public class ClearanceHub {

    private final String project;

    private final ClearancePrompt prompt;

    private final Consumer<String> allow;

    private final Map<String, Verdict> decided = new ConcurrentHashMap<>();

    /**
     * Constructor.
     *
     * @param project Project name, shown to the operator.
     * @param prompt How the operator is asked.
     * @param allow Called with the address when the answer is yes.
     */
    public ClearanceHub(String project, ClearancePrompt prompt, Consumer<String> allow) {
        this.project = project;
        this.prompt = prompt;
        this.allow = allow;
    }

    /**
     * Handles one blocked connection.
     *
     * @param key Deduplication key for the destination.
     * @param address Address to allow if the answer is yes.
     * @param destination Destination as it should be shown.
     * @param protocol Protocol name.
     * @return The verdict in force for this destination.
     */
    public Verdict handle(String key, String address, String destination, String protocol) {

        final Verdict already = decided.get(key);
        if (already != null) {
            return already;
        }

        // Recorded before asking, so a burst of retries arriving while the notification is on
        // screen does not produce a second notification.
        decided.put(key, Verdict.DENY);

        final Verdict verdict = prompt.ask(
                new ClearanceRequest(project, destination, address, protocol));
        decided.put(key, verdict);

        if (verdict.allows()) {
            allow.accept(address);
        }
        return verdict;
    }

    /**
     * Returns the destinations that have been decided, and how.
     *
     * @return Decisions so far.
     */
    public Map<String, Verdict> decisions() {
        return Map.copyOf(decided);
    }

    /**
     * Returns the destinations that were allowed.
     *
     * @return Allowed keys.
     */
    public Set<String> allowed() {
        return decided.entrySet().stream()
                .filter(entry -> entry.getValue().allows())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
