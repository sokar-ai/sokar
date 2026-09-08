package org.fuin.sokar.clearance;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Decides what happens to each blocked connection, once per destination.
 * <p>
 * <strong>Deduplication is not an optimisation here, it is what makes the feature usable.</strong>
 * A blocked connection is retried: an agent's HTTP client, a package manager or a build tool will
 * produce hundreds of identical drop events a second. Asking once per event would bury the desktop
 * and make the answer meaningless.
 * <p>
 * A destination that has been asked about is never asked about again for the life of the task, in
 * either direction. Re-asking after a deny would let an agent extract an allow by simply retrying
 * until the operator's attention slipped - and that is why {@link #restore(List)} exists: the hub
 * of a resumed task starts empty, so without reading its decisions back the retry only has to
 * outlast a watcher rather than an operator.
 */
public class ClearanceHub {

    private final String project;

    private final String task;

    private final ClearancePrompt prompt;

    private final java.util.function.BiConsumer<String, String> allow;

    private final Map<String, Verdict> decided = new ConcurrentHashMap<>();

    private final Map<String, Blocked> seen = new ConcurrentHashMap<>();

    private final List<Consumer<Decision>> listeners = new CopyOnWriteArrayList<>();

    /** Names this run was granted while it was running; asked afresh, because grants arrive late. */
    private java.util.function.Predicate<String> granted = name -> false;

    /**
     * Constructor.
     *
     * @param project Project name, shown to the operator.
     * @param task Task name, shown to the operator.
     * @param prompt How the operator is asked.
     * @param allow Called with the address when the answer is yes.
     */
    public ClearanceHub(String project, String task, ClearancePrompt prompt,
            java.util.function.BiConsumer<String, String> allow) {
        this.project = project;
        this.task = task;
        this.prompt = prompt;
        this.allow = allow;
    }

    /**
     * Registers something to be told about each decision, once, when it is first reached.
     * <p>
     * Once and not per event: the journal and any interface hang off this, and a destination an
     * agent retries a hundred times a second is one decision. A listener that throws is not caught
     * here - the ones that can fail know what to say about it, and this class does not.
     *
     * @param listener Told about each new decision.
     */
    public void onDecision(Consumer<Decision> listener) {
        listeners.add(listener);
    }

    /**
     * Says which names this run has been granted, so they are not asked about.
     * <p>
     * A predicate rather than a list, and consulted on every event rather than once: a grant is
     * made while the task runs, usually because an agent has just been refused something, so a
     * copy taken at start would never contain the grant that matters.
     *
     * @param names Answers whether a name has been granted to this run.
     */
    public void granted(java.util.function.Predicate<String> names) {
        this.granted = names;
    }

    /**
     * Handles one blocked connection.
     *
     * @param blocked What the firewall stopped.
     * @return The verdict in force for this destination.
     */
    public Verdict handle(Blocked blocked) {

        final String key = blocked.key();
        final Verdict already = decided.get(key);
        if (already != null) {
            return already;
        }

        // Somebody has already said this run may reach that name, so there is nothing to ask. The
        // first connection to it was still dropped - the packet is gone and the agent will retry -
        // and every one after this is not.
        if (blocked.name() != null && granted.test(blocked.name())) {
            decided.put(key, Verdict.ALLOW);
            seen.put(key, blocked);
            allow.accept(blocked.destination(), blocked.name());
            announce(blocked, Verdict.ALLOW, Decision.GRANTED);
            return Verdict.ALLOW;
        }

        // Recorded before asking, so a burst of retries arriving while the notification is on
        // screen does not produce a second notification.
        decided.put(key, Verdict.DENY);
        seen.put(key, blocked);

        final Verdict verdict = prompt.ask(new ClearanceRequest(project, task, blocked.shown(),
                blocked.destination(), blocked.protocol()));
        decided.put(key, verdict);

        if (verdict.allows()) {
            allow.accept(blocked.destination(), blocked.name());
        }
        announce(blocked, verdict, Decision.PROMPT);
        return verdict;
    }

    /**
     * Records a verdict that came from somewhere other than this hub's own prompt.
     * <p>
     * Used by the varlink {@code Verdict} method, so an operator can answer from a client that is
     * not the notification Sokar raised. It may arrive for a destination this hub never asked
     * about - the event reaches subscribers before the answer is waited for - and it may arrive
     * after a prompt ran out, which is the only way an expired question is ever answered.
     *
     * @param key Deduplication key for the destination.
     * @param address Address to allow if the verdict allows it.
     * @param verdict What was decided.
     */
    public void decide(String key, String address, Verdict verdict) {
        final Blocked blocked = seen.computeIfAbsent(key, unknown ->
                Blocked.fromKey(unknown, address));
        final Verdict previous = decided.put(key, verdict);
        if (verdict.allows() && (previous == null || !previous.allows())) {
            // No name here: a verdict from a client names an address, and the name it
            // was reached by is not part of that call.
            allow.accept(address, null);
        }
        announce(blocked, verdict, Decision.CLIENT);
    }

    /**
     * Takes back the decisions of an earlier watcher, without asking anything or recording
     * anything.
     * <p>
     * <strong>An allow is applied again, not only remembered.</strong> A resumed task gets a fresh
     * network namespace and the hook rebuilds its ruleset from the project, so the addresses the
     * operator cleared last time are no longer in it: remembering them would leave the hub
     * answering allow for a destination the firewall still drops, which is the worst of the three
     * possible states because nothing asks again.
     * <p>
     * Nothing is announced. These decisions are already in the journal, and writing them again on
     * every resume would make the record grow with the number of restarts rather than with the
     * number of decisions.
     *
     * @param decisions What was decided before, oldest first.
     */
    public void restore(List<Decision> decisions) {
        final Map<String, Decision> last = new LinkedHashMap<>();
        for (final Decision decision : decisions) {
            last.put(decision.key(), decision);
        }
        for (final Decision decision : last.values()) {
            decided.put(decision.key(), decision.verdict());
            seen.put(decision.key(), new Blocked(decision.destination(), decision.port(),
                    decision.protocol(), decision.shown()));
            if (decision.verdict().allows()) {
                // Replayed after a restart. The name is not in the journal, and it does not
                // need to be: what was recorded at grant time is already on disk.
                allow.accept(decision.destination(), null);
            }
        }
    }

    private void announce(Blocked blocked, Verdict verdict, String source) {
        final Decision decision = new Decision(Instant.now(), project, task, blocked.key(),
                blocked.destination(), blocked.port(), blocked.protocol(), blocked.shown(),
                verdict, source);
        listeners.forEach(listener -> listener.accept(decision));
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
