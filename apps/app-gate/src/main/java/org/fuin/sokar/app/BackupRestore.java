package org.fuin.sokar.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.gate.GitGate;

/**
 * Restores a project's mirror from a bundle, refusing when that would discard work nobody reviewed.
 * <p>
 * <strong>The refusal is the requirement, not the restore.</strong> `gate restore` already refused
 * to write over an existing mirror - but with one sentence about "whatever has been pushed since
 * the backup", which is true and says nothing about what is actually at stake. Unreviewed pushes
 * exist <em>only</em> in the mirror: they are not on the upstream, not in a workspace, and not in
 * the bundle being restored. Overwriting one destroys the only copy.
 * <p>
 * So this names them, and uses the outcome the rest of the product already uses for the same
 * situation - {@code HOLDS_WORK}, as {@code DeleteProject} and {@code Stop} both answer.
 */
public final class BackupRestore {

    /** What happened. */
    public enum Outcome {

        /** The mirror was restored from the bundle. */
        RESTORED,

        /** What would happen, having changed nothing. */
        PREVIEWED,

        /** Refused: the mirror holds pushes nobody has reviewed. */
        HOLDS_WORK,

        /** No project of that name here. */
        NO_SUCH_PROJECT,

        /** No bundle at that path, or not a usable one. */
        NO_SUCH_BACKUP,

        /** The restore failed. */
        FAILED
    }

    /**
     * What a restore did.
     *
     * @param outcome What happened.
     * @param mirror The mirror it would write, or wrote.
     * @param unreviewed Refs nobody reviewed. Non-empty with {@code HOLDS_WORK}, and also under
     *        force - it is what force destroys.
     * @param detail Why it was refused or failed. "" otherwise.
     */
    public record Result(Outcome outcome, String mirror, List<String> unreviewed, String detail) { }

    private BackupRestore() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Restores a mirror from a bundle.
     *
     * @param context The machine.
     * @param project Project name, as {@code Projects} reports it.
     * @param bundle The bundle to restore from.
     * @param dryRun Says what would happen and changes nothing.
     * @param force Proceeds even though unreviewed work would be destroyed.
     * @return What happened.
     */
    public static Result restore(SokarContext context, String project, Path bundle,
            boolean dryRun, boolean force) {
        return restore(context, project, null, bundle, dryRun, force);
    }

    /**
     * Restores one repository's mirror from a bundle.
     * <p>
     * <strong>Which repository has to be said, because this writes over a mirror.</strong> A
     * bundle taken of one repository restored into another writes one history over another's, and
     * the pushes it destroys exist only in that mirror. Omitting it means the project's own
     * repository, which is the one the project file belongs to.
     *
     * @param context The machine.
     * @param project Project name, as {@code Projects} reports it.
     * @param repository Which of its repositories, or {@code null} for the project's own.
     * @param bundle The bundle to restore from.
     * @param dryRun Says what would happen and changes nothing.
     * @param force Proceeds even though unreviewed work would be destroyed.
     * @return What happened.
     */
    public static Result restore(SokarContext context, String project,
            @org.jspecify.annotations.Nullable String repository, Path bundle,
            boolean dryRun, boolean force) {

        final ProjectInventory.Summary summary = new ProjectInventory(context).projects().stream()
                .filter(candidate -> candidate.name().equals(project)).findFirst().orElse(null);
        if (summary == null || summary.file() == null) {
            return new Result(Outcome.NO_SUCH_PROJECT, "", List.of(),
                    "no project '" + project + "' is registered here");
        }
        if (!Files.isRegularFile(bundle)) {
            return new Result(Outcome.NO_SUCH_BACKUP, "", List.of(), "no bundle at " + bundle);
        }

        final GitGate gate;
        final Path mirror;
        try {
            final org.fuin.sokar.core.project.Project read =
                    GateSupport.project(Path.of(summary.file()));
            final org.fuin.sokar.core.project.Repository chosen =
                    GateSupport.repository(read, repository);
            // Built from the context rather than through GateSupport, which derives its paths
            // from the real environment and its runner from nothing - so anything going through
            // it reaches this machine's directories whatever it was handed. That is the same
            // shape as the listing that bypassed its runner, and it is why a test of this wrote
            // a mirror into a real home directory before it was noticed.
            final Path mirrors = context.paths().xdg().data().resolve("mirrors");
            mirror = chosen.name().equals(read.name())
                    ? mirrors.resolve(read.name() + ".git")
                    : mirrors.resolve(read.name()).resolve(chosen.name() + ".git");
            gate = new GitGate(context.runner(), mirror,
                    org.fuin.sokar.gate.GateMode.of(read.securityClass()), chosen.upstream());
        } catch (RuntimeException ex) {
            return new Result(Outcome.FAILED, "", List.of(), String.valueOf(ex.getMessage()));
        }

        // Read before anything is touched, and reported whatever happens next: somebody deciding
        // whether to force needs to see the cost, and somebody who forced needs it in the record.
        final List<String> unreviewed = Files.isDirectory(mirror.resolve("objects"))
                ? gate.pending() : List.of();

        if (!unreviewed.isEmpty() && !force) {
            return new Result(Outcome.HOLDS_WORK, mirror.toString(), unreviewed,
                    mirror + " holds " + unreviewed.size() + " push(es) nobody has reviewed."
                    + " They exist only here - not on the upstream, not in the bundle - so"
                    + " restoring over them destroys the only copy");
        }
        if (dryRun) {
            return new Result(Outcome.PREVIEWED, mirror.toString(), unreviewed, "");
        }

        try {
            if (Files.isDirectory(mirror.resolve("objects"))) {
                // restore() refuses to write over a mirror, which is right: it is the last guard
                // before something irreversible. Moving it aside is the deliberate act, taken
                // only after the refusal above has been answered.
                gate.deleteMirror();
            }
            gate.restore(bundle);
            return new Result(Outcome.RESTORED, mirror.toString(), unreviewed, "");
        } catch (RuntimeException ex) {
            return new Result(Outcome.FAILED, mirror.toString(), unreviewed,
                    String.valueOf(ex.getMessage()));
        }
    }
}
