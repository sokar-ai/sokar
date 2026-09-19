package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

        /** The fetched commit carries no signature at all. Usually somebody forgot to sign. */
        NOT_SIGNED,

        /**
         * A good signature by a key this machine was never given.
         * <p>
         * Its own outcome rather than one refusal with the others, because the two are different
         * events with different cures: a key that has moved, or somebody putting a project file
         * past the machine. Only this one is worth a person's attention as a possible attempt.
         */
        UNKNOWN_KEY,

        /** Nothing is pinned here, so nothing can be verified and nothing may be applied. */
        NO_ANCHOR,

        /** The commit or the repository could not be read. */
        UNREADABLE,

        /** The repository could not be reached. What was verified before still runs. */
        UNREACHABLE,

        /**
         * This account's vault is shut, so the credential a private repository needs is out of
         * reach.
         * <p>
         * Separate from {@link #UNREACHABLE} because the action is different: one is <em>unlock
         * your vault</em>, the other is <em>look at the network or the URL</em>. Reported as
         * unreachable, it was true and misleading.
         */
        VAULT_LOCKED,

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
     * @param refused The commit that was rejected, or "" when nothing was. <strong>Not the same
     *        field as {@code commit}</strong>: what is running and what was turned away are
     *        different questions, and a screen asks both.
     * @param signer The fingerprint of the key that signed the refused commit, or "" when there is
     *        none to name. Only the fingerprint, which is not a secret.
     */
    public record Result(Outcome outcome, String commit, String detail, String refused,
            String signer) {

        /**
         * Constructor for an outcome that turned nothing away.
         *
         * @param outcome What happened.
         * @param commit The commit now in force.
         * @param detail What to tell an operator.
         */
        public Result(Outcome outcome, String commit, String detail) {
            this(outcome, commit, detail, "", "");
        }

        /**
         * Tells whether this is a state a person has to do something about.
         *
         * @return {@code true} when nothing will change until somebody acts - which is not the
         *         same as a failure that the next pass may fix by itself.
         */
        public boolean needsAPerson() {
            return outcome == Outcome.UNKNOWN_KEY || outcome == Outcome.NOT_SIGNED
                    || outcome == Outcome.NO_ANCHOR || outcome == Outcome.REWRITTEN
                    || outcome == Outcome.UNUSABLE || outcome == Outcome.VAULT_LOCKED;
        }
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
                //
                // A shut vault is its own outcome rather than a sentence appended to this one.
                // Reported as unreachable it was true and misleading: nothing is wrong with the
                // network or the URL, and the one thing that helps is unlocking.
                // A vault that EXISTS and is shut. A machine with no vault at all has no
                // credential locked away, so nothing about it explains a failed fetch - saying
                // "unlock your vault" there would send somebody to a vault they have not made.
                if (context.vault().exists() && context.opener().isEmpty()) {
                    return new Result(Outcome.VAULT_LOCKED, followed.commit(),
                            "cannot fetch " + followed.url() + " while this account's vault is"
                                    + " shut, so the credential a private repository needs is out"
                                    + " of reach: " + fetched.standardError().strip());
                }
                return new Result(Outcome.UNREACHABLE, followed.commit(),
                        "cannot fetch " + followed.url() + ": " + fetched.standardError().strip());
            }

            final ConfigurationGate.Verdict verdict = gate.verify(clone, "FETCH_HEAD");
            if (followed.unverified()) {
                // Followed without an anchor, on purpose. Nothing is checked, and the state says
                // so everywhere this project is shown - the refusal is skipped, not the reporting.
                return applied(runner, clone, followed, verdict.commit().isEmpty()
                        ? headOf(runner, clone) : verdict.commit());
            }
            if (!verdict.mayApply()) {
                // The gate already tells these apart; collapsing them into one refusal was
                // throwing away the only thing that says which of them happened - and the commit
                // that was turned away with it.
                return new Result(switch (verdict.outcome()) {
                    case NOT_SIGNED -> Outcome.NOT_SIGNED;
                    case UNKNOWN_KEY -> Outcome.UNKNOWN_KEY;
                    case NO_ANCHOR -> Outcome.NO_ANCHOR;
                    default -> Outcome.UNREADABLE;
                }, followed.commit(), verdict.detail(), verdict.commit(), verdict.signer());
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

            return applied(runner, clone, followed, verdict.commit());
        } catch (final IOException | RuntimeException ex) {
            return new Result(Outcome.FAILED, followed.commit(), String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Moves the working tree to a commit and says what it replaced.
     *
     * @param runner How git is run.
     * @param clone The followed clone.
     * @param followed What is recorded.
     * @param commit What to move to.
     * @return What happened.
     */
    private Result applied(final CommandRunner runner, final Path clone,
            final FollowedProjects.Followed followed, final String commit) {
        if (commit.equals(followed.commit())) {
            return new Result(Outcome.UNCHANGED, followed.commit(), "");
        }
        try {
            // Read before the reset, because afterwards there is nothing left to see. The rule is
            // stated rather than discovered: the repository wins for what it covers, and a local
            // edit to something reconciled is replaced - but the machine says it replaced it
            // rather than doing it quietly. A file the repository does not cover is untracked, and
            // 'reset --hard' does not touch it.
            final List<String> replaced = locallyChanged(runner, clone);

            // Only now does the working tree move. Everything above this line is reversible and
            // changes nothing an operator would notice.
            final CommandResult moved = runner.run(Command.of("git", "-C", clone.toString(),
                    "reset", "--hard", "--quiet", commit));
            if (!moved.successful()) {
                return new Result(Outcome.FAILED, followed.commit(),
                        "have " + commit + " and could not check it out: "
                                + moved.standardError().strip());
            }
            final Path file = clone.resolve("project.yml");
            try {
                final org.fuin.sokar.core.project.Project read = ProjectReader.read(file);
                if (!read.name().equals(followed.name())) {
                    // Following it as one name while the file calls itself another would give the
                    // machine a project whose containers, images and mirrors are all named after
                    // the file - and a name in 'project following' that resolves to none of them.
                    return new Result(Outcome.UNUSABLE, followed.commit(),
                            "this is followed as '" + followed.name() + "' and the file calls"
                                    + " itself '" + read.name() + "'. Follow it under its own"
                                    + " name, or change the name in the file.");
                }
                // The rest of what a project file has to survive on THIS machine: an egress set
                // it does not have. Checked here because here is where the file arrives - the
                // alternative was a call that checked before anything was written, which is a
                // second place the same question could be answered differently.
                //
                // Every other check is the reader's: the name's shape, the security class, the
                // base image, and an online project without an upstream.
                for (final org.fuin.sokar.core.project.Repository repository
                        : read.allRepositories()) {
                    context.paths().egressSets().origins(
                            read.egressFor(repository).sets());
                }
            } catch (final ProjectException ex) {
                // Accepted and still not a project. Its own outcome, because "somebody committed
                // something broken" is a different problem from "somebody signed nothing", and
                // only one of them is about trust.
                return new Result(Outcome.UNUSABLE, followed.commit(),
                        file.getFileName() + " does not read as a project: " + ex.getMessage());
            } catch (final org.fuin.sokar.shield.EgressSetException ex) {
                // Refused here rather than at the first task start, which is minutes later and
                // somewhere else - and would read as a broken build rather than as a project file
                // naming something this machine has never had.
                return new Result(Outcome.UNUSABLE, followed.commit(),
                        file.getFileName() + " names an egress set this machine does not have: "
                                + ex.getMessage());
            }
            return new Result(Outcome.APPLIED, commit, replaced.isEmpty() ? ""
                    : "replaced a local edit to " + String.join(", ", replaced));
        } catch (final RuntimeException ex) {
            return new Result(Outcome.FAILED, followed.commit(), String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Returns the commit a fetch brought, without asking whether anybody signed it.
     * <p>
     * Only the unverified path needs this: the gate reads the commit on its way to a verdict, and
     * a machine following without an anchor never asks for one.
     *
     * @param runner How git is run.
     * @param clone The followed clone.
     * @return The commit, or "" when it cannot be read.
     */
    private static String headOf(final CommandRunner runner, final Path clone) {
        final CommandResult found = runner.run(Command.of("git", "-C", clone.toString(),
                "rev-parse", "FETCH_HEAD"));
        return found.successful() ? found.standardOutput().strip() : "";
    }

    /**
     * Returns the tracked files a reset will overwrite, so the machine can say it did.
     * <p>
     * Tracked only: an untracked file is one the repository does not cover, and
     * {@code reset --hard} leaves it where it is. Reporting it as replaced would be a lie in the
     * direction that makes somebody look for work that is still there.
     *
     * @param runner How git is run.
     * @param clone The followed clone.
     * @return Paths, empty when nothing local was changed - or when git cannot be asked, because a
     *         reconciliation must not fail over a question about what it is about to replace.
     */
    private static List<String> locallyChanged(final CommandRunner runner, final Path clone) {
        final CommandResult status = runner.run(Command.of("git", "-C", clone.toString(),
                "status", "--porcelain", "--untracked-files=no"));
        if (!status.successful()) {
            return List.of();
        }
        return status.standardOutput().lines()
                .map(String::strip)
                .filter(line -> line.length() > 3)
                .map(line -> line.substring(2).strip())
                .sorted()
                .toList();
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
        // 'unverified' is carried, not defaulted. It was dropped here: a follow written with the
        // flag was rewritten by this method one line later, so a project taken WITHOUT an anchor
        // reported itself as one whose signature had been checked - the single field that tells a
        // person that whoever can push there decides what this machine runs. Found by Agent
        // Frontend, whose dialog shows it on every project.
        return new FollowedProjects.Followed(followed.name(), followed.url(), result.commit(),
                Instant.now().toString(), result.outcome().name(), result.detail(),
                result.refused(), result.signer(), followed.unverified());
    }
}
