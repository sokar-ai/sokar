package org.fuin.sokar.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.gate.ReviewRanking;
import org.fuin.sokar.wire.TaskProfile;
import org.jspecify.annotations.Nullable;

/**
 * What a person reads before a push's patch: what the task was asked, and what the push changes, the
 * files to read first at the top.
 * <p>
 * <strong>The instruction is shown, never compared.</strong> Sokar knows what a task was asked only when
 * it was started with a prompt; a person typing into an attached session tells it nothing. Matching the
 * files against the instruction would be a signal present half the time and a guess when it is, and a
 * reviewer would lean on it wrongly. So the instruction is put where the reviewer reads it, and the
 * judgment of what else happened stays theirs.
 */
public final class ReviewText {

    /**
     * What is known about what a push's task was asked.
     *
     * @param found Whether the task that pushed is still known on this machine.
     * @param prompt What it was asked, or {@code null} when it was started without an instruction.
     */
    public record Instruction(boolean found, @Nullable String prompt) {

        /** A push whose task this machine no longer knows. */
        static final Instruction GONE = new Instruction(false, null);

        /**
         * Says it in one line, for a person.
         *
         * @return The line.
         */
        String describe() {
            if (!found) {
                return "not known: the task that pushed this is gone, and what it was asked went with it";
            }
            return prompt == null || prompt.isBlank()
                    ? "none: the task was started without one, and what a person typed into it is not kept"
                    : prompt;
        }
    }

    private ReviewText() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Finds what the task that made a push was asked.
     * <p>
     * A gated task pushes to {@code refs/sokar/incoming/<task>} in its project's gate, so the push's name
     * is the task's, and the task's own record says what it was asked: in its runtime directory while it
     * exists, in the copy kept beside the state directory after a restart.
     *
     * @param paths Where this user's Sokar keeps its files.
     * @param project The project's name.
     * @param push The pending push's name.
     * @return What is known.
     */
    public static Instruction instruction(SokarPaths paths, String project, String push) {
        final String container = "sokar-" + project + "-" + push;
        for (final Path directory : List.of(paths.containerState(container), paths.taskRecord(container))) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            final TaskProfile profile = TaskProfile.readFrom(directory);
            if (profile != null && (GitGate.INCOMING + push).equals(profile.branch())) {
                return new Instruction(true, profile.prompt());
            }
        }
        return Instruction.GONE;
    }

    /**
     * Writes what a person reads before the patch.
     *
     * @param instruction What the task was asked.
     * @param files The ranked files.
     * @return The text, ending with a blank line.
     */
    static String render(Instruction instruction, List<ReviewRanking.File> files) {
        final StringBuilder out = new StringBuilder();
        out.append("asked: ").append(instruction.describe()).append("\n\n");
        section(out, files, ReviewRanking.Rank.DANGEROUS,
                "READ FIRST - dangerous by kind, however small:",
                "Nothing dangerous by kind: no CI definition, build script, dependency manifest or lockfile,"
                        + " nothing that runs on checkout, nothing made executable, no symbolic link.");
        section(out, files, ReviewRanking.Rank.ORDINARY, "Everything else:", null);
        final List<ReviewRanking.File> last = files.stream()
                .filter(file -> file.rank() == ReviewRanking.Rank.GENERATED
                        || file.rank() == ReviewRanking.Rank.REFORMATTING)
                .toList();
        if (!last.isEmpty()) {
            out.append("Almost never the finding - at the end of the patch:\n");
            last.forEach(file -> line(out, file));
            out.append('\n');
        }
        return out.toString();
    }

    private static void section(StringBuilder out, List<ReviewRanking.File> files, ReviewRanking.Rank rank,
            String heading, @Nullable String whenNone) {
        final List<ReviewRanking.File> these = files.stream().filter(file -> file.rank() == rank).toList();
        if (these.isEmpty()) {
            if (whenNone != null) {
                out.append(whenNone).append("\n\n");
            }
            return;
        }
        out.append(heading).append('\n');
        these.forEach(file -> line(out, file));
        out.append('\n');
    }

    private static void line(StringBuilder out, ReviewRanking.File file) {
        final String counts = file.added() < 0 ? "binary" : "+" + file.added() + " -" + file.removed();
        out.append("  ").append(file.status()).append("  ").append(file.path()).append("  ").append(counts);
        if (!file.reason().isEmpty()) {
            out.append("  ").append(file.reason());
        }
        out.append('\n');
    }
}
