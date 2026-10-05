package org.fuin.sokar.app;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;

/**
 * Decides whether a commit is one this machine may take its configuration from.
 * <p>
 * Everything else Sokar guards goes the other way: the gate holds what an agent wrote until a person
 * has read it. Configuration arriving from a repository reverses that direction, and the reversal is
 * what makes this necessary - <strong>a project's configuration says which hosts its tasks may
 * reach</strong>, so whoever can push to its repository could otherwise open the firewall on every
 * machine that follows it, at the next tick and without anybody looking.
 * <p>
 * <strong>The anchor is pinned out of band.</strong> One or more keys in an
 * {@code allowed_signers} file an operator wrote when the machine was prepared. A machine with none
 * verifies nothing and therefore applies nothing - which is the safe direction, and is reported
 * rather than left silent.
 * <p>
 * <strong>Verified by git rather than by us.</strong> A commit's signature covers the commit object,
 * and reproducing that canonicalisation here would be a second implementation of something git
 * already does exactly. So this runs {@code git verify-commit} with the pinned file and reads its
 * answer.
 */
public final class ConfigurationGate {

    /** What the machine decided about a commit. */
    public enum Outcome {

        /** Signed by a key this machine was told to trust. It may be applied. */
        VERIFIED,

        /** No signature at all. */
        NOT_SIGNED,

        /** A good signature, by a key that is not one of the pinned ones. */
        UNKNOWN_KEY,

        /** Nothing is pinned here, so nothing can be verified and nothing may be applied. */
        NO_ANCHOR,

        /** The repository or the commit could not be read. */
        UNREADABLE
    }

    /**
     * What one check found.
     *
     * @param outcome What was decided.
     * @param commit The commit that was checked, or "" when it could not be read.
     * @param signer The principal whose key signed it, or "" when there is none to name.
     * @param detail What to tell an operator, in words they can act on.
     */
    public record Verdict(Outcome outcome, String commit, String signer, String detail) {

        /**
         * Says whether this configuration may be applied.
         *
         * @return {@code true} only for {@link Outcome#VERIFIED}.
         */
        public boolean mayApply() {
            return outcome == Outcome.VERIFIED;
        }
    }

    private final CommandRunner runner;

    private final Path signers;

    /**
     * Constructor.
     *
     * @param runner How commands are run.
     * @param signers The pinned {@code allowed_signers} file.
     */
    public ConfigurationGate(final CommandRunner runner, final Path signers) {
        this.runner = runner;
        this.signers = signers;
    }

    /**
     * Checks one commit of a repository this machine follows.
     *
     * @param repository A clone on this machine.
     * @param ref What to check, usually {@code HEAD}.
     * @return What was decided.
     */
    /**
     * Checks one commit of a project against the keys pinned for that project alone.
     * <p>
     * Checked against every line of the file, any principal in it was accepted: a key pinned for a public project
     * verified a commit served for another one. Only the project's own lines are given to git here.
     *
     * @param repository A clone on this machine.
     * @param ref What to check.
     * @param project The project the commit is for, the principal its keys are pinned under.
     * @return What was decided.
     */
    public Verdict verify(final Path repository, final String ref, final String project) {
        if (!Files.isRegularFile(signers)) {
            return verify(repository, ref);
        }
        final Path own;
        try {
            own = Files.createTempFile("sokar-signers-", "");
            try {
                Files.write(own, Files.readAllLines(signers).stream()
                        .filter(line -> line.startsWith(project + " ")).toList());
                return new ConfigurationGate(runner, own).verify(repository, ref);
            } finally {
                Files.deleteIfExists(own);
            }
        } catch (final java.io.IOException ex) {
            return new Verdict(Outcome.UNREADABLE, "", "", "the pinned keys could not be read: " + ex.getMessage());
        }
    }

    public Verdict verify(final Path repository, final String ref) {
        if (!Files.isRegularFile(signers)) {
            return new Verdict(Outcome.NO_ANCHOR, "", "",
                    "no key is pinned in " + signers + ", so no configuration can be applied here."
                            + " Pin the key this project's configuration is signed with.");
        }
        final String commit = commitOf(repository, ref);
        final CommandResult result;
        try {
            // The commit as resolved, not the ref: a fetch between the two could make what was checked differ
            // from what is applied.
            result = runner.run(Command.of("git", "-C", repository.toString(),
                    "-c", "gpg.format=ssh",
                    "-c", "gpg.ssh.allowedSignersFile=" + signers.toAbsolutePath(),
                    "verify-commit", commit.isEmpty() ? ref : commit));
        } catch (final RuntimeException ex) {
            return new Verdict(Outcome.UNREADABLE, commit, "",
                    "git could not be run: " + ex.getMessage());
        }
        final String said = (result.standardError() + result.standardOutput()).strip();
        if (result.successful()) {
            return new Verdict(Outcome.VERIFIED, commit, principalIn(said), "");
        }
        if (commit.isEmpty()) {
            return new Verdict(Outcome.UNREADABLE, "", "",
                    "'" + ref + "' is not something " + repository + " has: " + said);
        }
        // Measured against git 2.53: an unsigned commit fails with nothing to say, and one signed
        // by a key that is not pinned reports a good signature and "No principal matched". The two
        // have different cures - somebody forgot to sign, or somebody who may not sign did - and
        // an operator should not have to tell them apart from the same sentence.
        if (said.contains("No principal matched") || said.contains("Good \"git\" signature with")) {
            // The fingerprint, because it is what turns "signed by a key you were not given" into
            // something a person can act on: they compare it with the one they meant to pin. A
            // public key's fingerprint is not a secret, and nothing else about the key is said.
            final String fingerprint = fingerprintIn(said);
            return new Verdict(Outcome.UNKNOWN_KEY, commit, fingerprint,
                    "signed by a key that is not pinned on this machine, so it is not applied"
                            + (fingerprint.isEmpty() ? "" : ": " + fingerprint));
        }
        return new Verdict(Outcome.NOT_SIGNED, commit, "",
                "carries no signature, so it is not applied");
    }

    /**
     * Reads the key's fingerprint out of git's own sentence.
     * <p>
     * git prints {@code Good "git" signature with ED25519 key SHA256:<base64>} for a signature it
     * could check but not attribute. Taken by its shape rather than by position, so a git that
     * words the rest of the line differently still gives up the fingerprint.
     *
     * @param said What git wrote.
     * @return The fingerprint, or "" when there is none in there.
     */
    private static String fingerprintIn(final String said) {
        final java.util.regex.Matcher found =
                java.util.regex.Pattern.compile("SHA256:[A-Za-z0-9+/]{43}").matcher(said);
        return found.find() ? found.group() : "";
    }

    private String commitOf(final Path repository, final String ref) {
        try {
            final CommandResult result = runner.run(Command.of("git", "-C", repository.toString(),
                    "rev-parse", ref));
            return result.successful() ? result.standardOutput().strip() : "";
        } catch (final RuntimeException ex) {
            return "";
        }
    }

    /**
     * Reads the principal out of git's own sentence.
     *
     * @param said What git printed.
     * @return The principal, or "" when the sentence does not name one.
     */
    private static String principalIn(final String said) {
        final int at = said.indexOf("signature for ");
        if (at < 0) {
            return "";
        }
        final String rest = said.substring(at + "signature for ".length());
        final int end = rest.indexOf(" with ");
        return end < 0 ? rest.strip() : rest.substring(0, end).strip();
    }
}
