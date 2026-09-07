package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a task holds that never reached the gate, and the note a stopping task leaves about it.
 * <p>
 * A running container can be asked. A stopped one cannot - {@code podman exec} needs a running
 * container - and removing a task is exactly when nobody is running it any more. Measured: a task
 * stopped first and purged afterwards was removed silently, with its unpushed commit, reporting
 * success. The refusal only ever covered a task that was still up.
 * <p>
 * So the answer is written down while it is still knowable, and read back by whoever removes the
 * task later. It cannot go stale in between: a stopped container's filesystem does not change, and
 * a task that runs again writes the note again when it next stops.
 * <p>
 * <strong>Reading the workspace from the host instead was rejected.</strong> {@code .git} is
 * agent-controlled content: hooks, {@code core.pager}, {@code core.fsmonitor} and aliases all run
 * host-side code, so {@code git status} in it is enough to run whatever the agent wrote. Keeping
 * the host out of that directory is the property this requirement exists to protect, and a
 * convenience check is not a reason to give it up.
 */
public final class UnhandedWork {

    /** Name of the note in a task's state directory. */
    static final String FILE = "unhanded-work";

    private UnhandedWork() {
        throw new UnsupportedOperationException("It is not allowed to create an instance");
    }

    /**
     * Turns the workspace census into a phrase naming what would be lost.
     *
     * @param census Two counts, changed files then commits, as the workspace reported them.
     * @return A phrase such as {@code 2 commits and 3 changed files}, or {@code null} when the
     *         workspace holds nothing that is not already on the gate.
     */
    public static @Nullable String phrase(String census) {
        final String[] counts = census.strip().split("\\s+");
        if (counts.length != 2) {
            return null;
        }
        final int changed;
        final int commits;
        try {
            changed = Integer.parseInt(counts[0]);
            commits = Integer.parseInt(counts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (changed == 0 && commits == 0) {
            return null;
        }
        final StringBuilder text = new StringBuilder();
        if (commits > 0) {
            text.append(commits).append(commits == 1 ? " commit" : " commits");
        }
        if (changed > 0) {
            text.append(text.isEmpty() ? "" : " and ")
                    .append(changed).append(changed == 1 ? " changed file" : " changed files");
        }
        return text.toString();
    }

    /**
     * Writes what the task holds, so that removing it later can still say so.
     * <p>
     * Written even when there is nothing: "this task held nothing" and "nobody looked" are
     * different answers, and only one of them makes it safe to remove a task without asking.
     *
     * @param state The task's state directory.
     * @param phrase What the task holds, or {@code null} for nothing.
     */
    public static void note(Path state, @Nullable String phrase) {
        try {
            if (Files.isDirectory(state)) {
                Files.writeString(state.resolve(FILE), phrase == null ? "" : phrase,
                        StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            // The task stops regardless. A note that could not be written reads as "nobody
            // looked", which is the cautious end of this: removal then asks rather than proceeds.
        }
    }

    /**
     * Returns the note a stopping task left.
     *
     * @param state The task's state directory.
     * @return Empty when there is no note at all, which means nothing knows what the task holds;
     *         a blank string when the task held nothing; otherwise the phrase naming what it
     *         holds.
     */
    public static Optional<String> note(Path state) {
        final Path file = state.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8).strip());
        } catch (IOException ex) {
            return Optional.empty();
        }
    }
}
