package org.fuin.sokar.acceptance;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Tells an agent that repeats a failure from one that is still working, from what has scrolled into its tasks'
 * histories.
 * <p>
 * <strong>What scrolled by, not what is on the screen.</strong> A spinner, a timer and tmux's status bar redraw in
 * place, so the same line is drawn again many times a minute by an agent that works; they never scroll into the
 * history. A tool call that fails and is tried again adds a new block each time, which does. Measured on a run whose
 * agent wrote one file thousands of times, each try refused the same way, while the step waited out its four minutes.
 * <p>
 * <strong>Two rules.</strong> The marker a scenario declares for a failed tool call, seen {@value #MARKER_REPEATS}
 * times since the step began, is a loop: three tries of one failing call is no longer working on it. Without a marker,
 * or for a loop the marker does not catch, one line seen more often than {@link #REPEATS} allows is one. Lines without
 * a letter or a digit do not count: the bottom edge of every box an agent draws is the same line.
 * <p>
 * Counted from the step's own beginning: what scrolled by before it, in an earlier step, is the agent's past.
 */
final class LoopWatch {

    /** How often the declared marker may scroll by in one step before it is a loop. */
    static final int MARKER_REPEATS = 3;

    /** The system property that sets how often one line may scroll by in one step. */
    static final String REPEATS = "sokar.acceptance.repeats";

    /** How often one line may scroll by in one step when {@link #REPEATS} is not set. */
    static final int DEFAULT_REPEATS = 25;

    /** What separates one container's history from the next in what {@link #COMMAND} prints. */
    static final char SEPARATOR = '\u001e';

    /** Prints the history of every task's session of the account, each after a line naming its container. */
    static final String COMMAND = "for c in $(podman ps --format '{{.Names}}' --filter name=^sokar-); do"
            + " printf '\\036%s\\n' \"$c\"; podman exec \"$c\" tmux capture-pane -p -J -S - -E -1 2>/dev/null; done";

    private final @Nullable String marker;

    private final int repeats;

    private @Nullable Map<String, Map<String, Integer>> baseline;

    /**
     * Creates one for a step.
     *
     * @param marker What a failed tool call shows, as a scenario declared it, or {@code null}.
     * @param repeats How often one line may scroll by.
     */
    LoopWatch(@Nullable String marker, int repeats) {
        this.marker = marker;
        this.repeats = repeats;
    }

    /**
     * Reads how often one line may scroll by from {@link #REPEATS}.
     *
     * @return The limit.
     */
    static int repeats() {
        final String set = System.getProperty(REPEATS);
        if (set == null || set.isBlank()) {
            return DEFAULT_REPEATS;
        }
        try {
            return Integer.parseInt(set.strip());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(REPEATS + " is a number of lines, not '" + set + "'", ex);
        }
    }

    /** Forgets what was seen, so the next look is the beginning of a step. */
    void reset() {
        baseline = null;
    }

    /**
     * Looks at the histories once.
     * <p>
     * The first look after {@link #reset()} is what the step began with, and finds nothing.
     *
     * @param histories Each task's history, by its container.
     * @return What repeated, where and how often, or empty while nothing does.
     */
    Optional<String> check(Map<String, String> histories) {
        final Map<String, Map<String, Integer>> now = new LinkedHashMap<>();
        histories.forEach((container, history) -> now.put(container, counts(history)));
        final Map<String, Map<String, Integer>> before = baseline;
        if (before == null) {
            baseline = now;
            return Optional.empty();
        }
        for (final Map.Entry<String, Map<String, Integer>> task : now.entrySet()) {
            // A container that appeared during the step began with nothing.
            final Map<String, Integer> was = before.getOrDefault(task.getKey(), Map.of());
            int failures = 0;
            String failure = "";
            for (final Map.Entry<String, Integer> line : task.getValue().entrySet()) {
                final int added = line.getValue() - was.getOrDefault(line.getKey(), 0);
                if (marker != null && line.getKey().contains(marker) && added > 0) {
                    failures += added;
                    failure = line.getKey();
                }
                if (added > repeats) {
                    return Optional.of("In " + task.getKey() + " one line scrolled by " + added
                            + " times since this step began, more than the " + repeats + " that " + REPEATS
                            + " allows: \"" + line.getKey() + "\"");
                }
            }
            if (failures >= MARKER_REPEATS) {
                return Optional.of("In " + task.getKey() + " the tool failure \"" + marker + "\" scrolled by " + failures
                        + " times since this step began: the agent repeats a failing call. The last: \"" + failure + "\"");
            }
        }
        return Optional.empty();
    }

    /**
     * Splits what {@link #COMMAND} printed into each container's history.
     *
     * @param printed Its output.
     * @return Each history, by its container.
     */
    static Map<String, String> histories(String printed) {
        final Map<String, String> histories = new LinkedHashMap<>();
        String container = null;
        StringBuilder history = new StringBuilder();
        for (final String line : printed.split("\n")) {
            if (!line.isEmpty() && line.charAt(0) == SEPARATOR) {
                if (container != null) {
                    histories.put(container, history.toString());
                }
                container = line.substring(1).strip();
                history = new StringBuilder();
            } else if (container != null) {
                history.append(line).append('\n');
            }
        }
        if (container != null) {
            histories.put(container, history.toString());
        }
        return histories;
    }

    private static Map<String, Integer> counts(String history) {
        final Map<String, Integer> counts = new HashMap<>();
        for (final String raw : history.split("\n")) {
            final String line = raw.strip();
            if (line.codePoints().anyMatch(Character::isLetterOrDigit)) {
                counts.merge(line, 1, Integer::sum);
            }
        }
        return counts;
    }
}
