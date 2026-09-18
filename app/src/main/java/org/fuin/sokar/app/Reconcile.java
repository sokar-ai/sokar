package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;

/**
 * Brings this account's copy of a project's repository up to what the project says, when the
 * project can prove it said it.
 * <p>
 * <strong>Fetch, then verify, then move.</strong> Never the other way round: the working tree is
 * left where it was until the fetched commit has been checked, so a machine that is handed
 * something unsigned keeps running what it last verified rather than running what arrived. That is
 * what "refused and reported, never applied and never silently skipped" means in code.
 * <p>
 * <strong>Applying points rather than copies.</strong> The project's file is used where the clone
 * holds it, so there is no second copy on the machine to drift from the first. A local edit to the
 * clone is undone by the next fetch, because the clone is state and not a working copy.
 * <p>
 * <strong>What is never reconciled</strong> is as important as what is: the vault, what a person
 * held, and running tasks. Nothing here touches them, and a commit that wanted to would have
 * nothing to act on.
 */
public final class Reconcile {

    /** What one attempt did. */
    public enum Outcome {

        /** The clone now holds a verified commit, and the project points at its file. */
        APPLIED,

        /** It already held that commit. Nothing was fetched into the working tree, nothing moved. */
        UNCHANGED,

        /** The fetched commit is not signed by a pinned key. What was verified before still runs. */
        REFUSED,

        /** The repository could not be reached. What was verified before still runs. */
        UNREACHABLE,

        /** The commit verifies and its project file does not read as a project. */
        UNUSABLE,

        /**
         * The verified commit is not a descendant of the one in force: the history moved
         * sideways or backwards. Not applied, and waiting for a person.
         */
        REWRITTEN,

        /** Something else, said in the detail. */
        FAILED
    }

    /**
     * What one attempt did.
     *
     * @param outcome What happened.
     * @param commit The commit now in force, or "" when none ever was.
     * @param detail What to tell an operator, or "".
     */
    public record Result(Outcome outcome, String commit, String detail) {
    }

    private final SokarContext context;

    private final ConfigurationGate gate;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    public Reconcile(final SokarContext context) {
        this.context = context;
        this.gate = new ConfigurationGate(context.runner(), context.paths().configurationSigners());
    }

    /**
     * Brings one followed project up to date.
     *
     * @param followed What this account knows about it.
     * @return What this attempt did.
     */
    public Result run(final FollowedProjects.Followed followed) {
        final Path clone = context.paths().followedClone(followed.name());
        final CommandRunner runner = context.runner();
        try {
            if (!Files.isDirectory(clone.resolve("HEAD")) && !Files.isDirectory(clone)) {
                Files.createDirectories(clone.getParent());
                final CommandResult made = runner.run(Command.of("git", "init", "-q",
                        clone.toString()));
                if (!made.successful()) {
                    return new Result(Outcome.FAILED, followed.commit(),
                            "cannot make " + clone + ": " + made.standardError().strip());
                }
            }
            final CommandResult fetched = runner.run(Command.of("git", "-C", clone.toString(),
                    "fetch", "--quiet", followed.url(), "HEAD"));
            if (!fetched.successful()) {
                // Unreachable is not a fault of the project's and not a reason to stop: what was
                // verified before is still what this machine runs.
                return new Result(Outcome.UNREACHABLE, followed.commit(),
                        "cannot fetch " + followed.url() + ": " + fetched.standardError().strip()
                                + vaultHint());
            }

            final ConfigurationGate.Verdict verdict = gate.verify(clone, "FETCH_HEAD");
            if (!verdict.mayApply()) {
                return new Result(Outcome.REFUSED, followed.commit(), verdict.detail());
            }
            if (verdict.commit().equals(followed.commit())) {
                return new Result(Outcome.UNCHANGED, followed.commit(), "");
            }
            if (!followed.commit().isEmpty() && !descends(clone, followed.commit(),
                    verdict.commit())) {
                // A properly signed commit that is not a descendant of the one in force. Somebody
                // with the key may have rebased - and somebody without it may be serving an older
                // signed configuration to put a rule back that was taken away, which needs no key
                // at all, only control of the route. The two look identical from here, so this
                // stops and asks rather than choosing.
                return new Result(Outcome.REWRITTEN, followed.commit(),
                        verdict.commit() + " is signed and is not a descendant of "
                                + followed.commit() + ", which is in force. The history was"
                                + " rewritten, or an older configuration is being served."
                                + " Accept it with 'sokar projects follow " + followed.name()
                                + " " + followed.url() + " --accept-rewrite'.");
            }

            // Only now does the working tree move. Everything above this line is reversible and
            // changes nothing an operator would notice.
            final CommandResult moved = runner.run(Command.of("git", "-C", clone.toString(),
                    "reset", "--hard", "--quiet", verdict.commit()));
            if (!moved.successful()) {
                return new Result(Outcome.FAILED, followed.commit(),
                        "verified " + verdict.commit() + " and could not check it out: "
                                + moved.standardError().strip());
            }
            final Path file = clone.resolve("project.yml");
            try {
                ProjectReader.read(file);
            } catch (final ProjectException ex) {
                // Signed by the right key and still not a project. Reported as its own outcome:
                // "somebody signed something broken" is a different problem from "somebody signed
                // nothing", and only one of them is about trust.
                return new Result(Outcome.UNUSABLE, followed.commit(),
                        "the commit is signed and " + file.getFileName() + " does not read as a"
                                + " project: " + ex.getMessage());
            }
            return new Result(Outcome.APPLIED, verdict.commit(), "");
        } catch (final IOException | RuntimeException ex) {
            return new Result(Outcome.FAILED, followed.commit(), String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Returns the likely cause when a private repository could not be fetched.
     *
     * @return A sentence, or "" when the vault is open and so is not the explanation.
     */
    /**
     * Says whether one commit descends from another.
     *
     * @param clone The repository.
     * @param older The commit in force.
     * @param newer The commit that was verified.
     * @return {@code true} when moving from one to the other only goes forwards. An older commit
     *         this clone no longer has counts as not descending - the safe answer.
     */
    private boolean descends(final Path clone, final String older, final String newer) {
        try {
            return context.runner().run(Command.of("git", "-C", clone.toString(), "merge-base",
                    "--is-ancestor", older, newer)).successful();
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    private String vaultHint() {
        return context.opener().isPresent() ? ""
                : ". This account's vault is shut, so nothing here holds a credential for it -"
                        + " open it if that repository needs one";
    }

    /**
     * Records the attempt against the project, so the state survives a restart.
     *
     * @param followed What was known before.
     * @param result What the attempt did.
     * @return What is now known.
     */
    public static FollowedProjects.Followed after(final FollowedProjects.Followed followed,
            final Result result) {
        return new FollowedProjects.Followed(followed.name(), followed.url(), result.commit(),
                Instant.now().toString(), result.outcome().name(), result.detail());
    }
}
