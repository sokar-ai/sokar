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

    /**
     * What a task holds, and whether that could be established at all.
     * <p>
     * <strong>Counts rather than a sentence.</strong> The phrase this used to carry was English,
     * with a fixed plural rule, and a client could neither show one of the numbers on its own nor
     * sort by it. The rendering belongs where the reader is; what crosses a boundary is what was
     * counted.
     * <p>
     * <strong>{@code readable} is not "holds nothing".</strong> Holding nothing is readable with
     * two zeros. Unreadable means nobody could look - a task killed, or stopped by a Sokar that
     * left no note - and absence has to be distinguishable from emptiness or it reads as nothing
     * to lose.
     *
     * @param readable Whether what it holds could be established.
     * @param changedFiles Files changed and not committed.
     * @param unpushedCommits Commits on no remote.
     * @param asOf When this was established, ISO-8601, or {@code null} when it is current.
     */
    public record Held(boolean readable, int changedFiles, int unpushedCommits,
            @Nullable String asOf) {

        /** @return An answer for a task nobody could ask. */
        public static Held unknown() {
            return new Held(false, 0, 0, null);
        }

        /** @return Whether anything at all is held. */
        public boolean anything() {
            return readable && (changedFiles > 0 || unpushedCommits > 0);
        }

        /**
         * Returns this as a phrase for a terminal, or {@code null} when nothing is held.
         *
         * @return Something like {@code 2 commits and 3 changed files}.
         */
        public @Nullable String phrase() {
            if (!anything()) {
                return null;
            }
            final StringBuilder text = new StringBuilder();
            if (unpushedCommits > 0) {
                text.append(unpushedCommits)
                        .append(unpushedCommits == 1 ? " commit" : " commits");
            }
            if (changedFiles > 0) {
                text.append(text.isEmpty() ? "" : " and ")
                        .append(changedFiles).append(changedFiles == 1 ? " changed file"
                                : " changed files");
            }
            return text.toString();
        }
    }

    /** Version of the note, so a format change is a decision rather than a surprise. */
    static final int VERSION = 1;

    /** Name of the note in a task's state directory. */
    static final String FILE = "unhanded-work";

    private UnhandedWork() {
        throw new UnsupportedOperationException("It is not allowed to create an instance");
    }

    /**
     * Turns the workspace census into what it holds.
     *
     * @param census Two counts, changed files then commits, as the workspace reported them.
     * @return What is held, current as of now. Unreadable when the census is not two numbers -
     *         the container answered something nobody can act on, which is not the same as
     *         answering that it holds nothing.
     */
    public static Held census(String census) {
        final String[] counts = census.strip().split("\\s+");
        if (counts.length != 2) {
            return Held.unknown();
        }
        try {
            return new Held(true, Integer.parseInt(counts[0]), Integer.parseInt(counts[1]), null);
        } catch (NumberFormatException ex) {
            return Held.unknown();
        }
    }


    /**
     * Writes down what a task holds, so that removing it later can still say so.
     * <p>
     * Written even when there is nothing: "this task held nothing" and "nobody looked" are
     * different answers, and only one of them makes it safe to remove a task without asking.
     *
     * @param state The task's state directory.
     * @param held What the task holds.
     */
    public static void note(Path state, Held held) {
        try {
            if (Files.isDirectory(state)) {
                final java.util.Map<String, Object> document = new java.util.LinkedHashMap<>();
                document.put("version", Integer.valueOf(VERSION));
                document.put("changedFiles", Integer.valueOf(held.changedFiles()));
                document.put("unpushedCommits", Integer.valueOf(held.unpushedCommits()));
                document.put("asOf", java.time.Instant.now().toString());
                Files.writeString(state.resolve(FILE), org.fuin.sokar.wire.Json.write(document),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ex) {
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
    public static Held read(Path state) {
        final Path file = state.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Held.unknown();
        }
        final String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            return Held.unknown();
        }
        if (content.startsWith("{")) {
            return fromJson(content);
        }
        // Written by a Sokar that recorded the phrase rather than the counts. Parsed rather than
        // discarded because it is our own sentence, in one of three shapes, and answering
        // "nobody looked" about a task that was measured would be worse than reading it back.
        return legacy(content, written(file));
    }

    private static Held fromJson(String content) {
        try {
            if (!(org.fuin.sokar.wire.Json.parse(content) instanceof java.util.Map<?, ?> map)) {
                return Held.unknown();
            }
            if (!(map.get("version") instanceof Number version) || version.intValue() != VERSION) {
                // A note from a later Sokar. Refusing to guess is what the sidecar does for the
                // same reason: a misread answer here decides whether work is destroyed.
                return Held.unknown();
            }
            final int changed = map.get("changedFiles") instanceof Number number
                    ? number.intValue() : 0;
            final int commits = map.get("unpushedCommits") instanceof Number number
                    ? number.intValue() : 0;
            final Object asOf = map.get("asOf");
            return new Held(true, changed, commits, asOf == null ? null : String.valueOf(asOf));
        } catch (RuntimeException ex) {
            return Held.unknown();
        }
    }

    private static Held legacy(String content, @Nullable String asOf) {
        if (content.isEmpty()) {
            // An empty note is the old way of recording "held nothing", and it meant it.
            return new Held(true, 0, 0, asOf);
        }
        final java.util.regex.Matcher commits =
                java.util.regex.Pattern.compile("(\\d+) commits?").matcher(content);
        final java.util.regex.Matcher changed =
                java.util.regex.Pattern.compile("(\\d+) changed files?").matcher(content);
        final boolean anyCommits = commits.find();
        final boolean anyChanged = changed.find();
        if (!anyCommits && !anyChanged) {
            return Held.unknown();
        }
        return new Held(true, anyChanged ? Integer.parseInt(changed.group(1)) : 0,
                anyCommits ? Integer.parseInt(commits.group(1)) : 0, asOf);
    }

    /**
     * Returns when the note was written, from the file itself.
     * <p>
     * The old format recorded no instant, and the file's own timestamp is the only thing that
     * knows. It is a good answer and not a perfect one - copying a state directory would carry it
     * along - which is why the new format writes the instant down.
     *
     * @param file The note.
     * @return ISO-8601, or {@code null}.
     */
    private static @Nullable String written(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant().toString();
        } catch (IOException ex) {
            return null;
        }
    }
}
