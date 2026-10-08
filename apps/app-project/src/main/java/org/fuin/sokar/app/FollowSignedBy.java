package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Follows a project, or says what following it would do, with the key a person named for it.
 * <p>
 * <strong>One way for the command line and the socket.</strong> The daemon's {@code Follow} took
 * {@code signedBy} and never read it, so a follow over the socket refused what the same follow at
 * the terminal applied. What a follow means cannot depend on where it was asked.
 * <p>
 * The key comes from the PERSON, never out of the repository it verifies. A whole key is pinned
 * as it came. A fingerprint - the string a refusal names, so the one a person has in front of
 * them - pins the key read out of the commit that was turned away, and only once its fingerprint
 * is the one they named. <strong>A check records nothing, not even the key</strong>: it verifies
 * against a copy of the pinned keys with the named one added, which is deleted afterwards.
 */
public final class FollowSignedBy {

    /**
     * What a follow or a check came to.
     *
     * @param result What the reconcile found.
     * @param recorded Whether anything of the project was written.
     * @param pinned The fingerprint pinned by this call, or "" when none was.
     * @param signers With several keys, each one's fingerprint in the order given - pinned by a follow, the ones a
     *        check would pin. Empty otherwise.
     */
    public record Answer(Reconcile.Result result, boolean recorded, String pinned, List<String> signers) {

        /**
         * Constructor for one key or none.
         *
         * @param result What the reconcile found.
         * @param recorded Whether anything of the project was written.
         * @param pinned The fingerprint pinned by this call, or "".
         */
        public Answer(final Reconcile.Result result, final boolean recorded, final String pinned) {
            this(result, recorded, pinned, List.of());
        }
    }

    /** A request that says opposite things, a key that is not one, or a key that did not sign it. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message What to tell the person.
         */
        public Refused(final String message) {
            super(message);
        }
    }

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    public FollowSignedBy(final SokarContext context) {
        this.context = context;
    }

    /**
     * Says what following would do, and records nothing.
     *
     * @param name The project's name.
     * @param url Its repository.
     * @param signedBy The key or fingerprint a person named, or {@code null}.
     * @param unverified Whether it would be followed without an anchor.
     * @return What following would do; {@code recorded} is always false.
     * @throws Refused If the request contradicts itself, or the named key did not sign it.
     */
    public Answer check(final String name, final String url, final @Nullable String signedBy,
            final boolean unverified) throws Refused {
        refuseReserved(name);
        refuseBoth(signedBy, unverified);
        final FollowedProjects.Followed followed = new FollowedProjects.Followed(name, url,
                "", "", "", "", "", "", unverified);
        if (signedBy == null) {
            return new Answer(new Reconcile(context).check(followed), false, "");
        }
        if (!SignedBy.isFingerprint(signedBy)) {
            return new Answer(withKey(name, line(name, signedBy), followed).result(), false, "");
        }
        final String named = signedBy.strip();
        final Reconcile.Checked first = new Reconcile(context).checked(followed);
        final String key = keyNamed(named, first);
        if (key == null) {
            return new Answer(first.result(), false, "");
        }
        return new Answer(withKey(name, line(name, key), followed).result(), false, "");
    }

    /**
     * Follows, pinning the named key, and reconciles once.
     * <p>
     * A first follow that cannot apply leaves nothing behind; a project already followed keeps its
     * record, because that record is where the reason lives.
     *
     * @param name The project's name.
     * @param url Its repository.
     * @param signedBy The key or fingerprint a person named, or {@code null}.
     * @param unverified Whether to follow without an anchor.
     * @param acceptRewrite Whether a person accepted a history rewrite.
     * @return What the follow did.
     * @throws Refused If the request contradicts itself, a key is not one, or the named key did not
     *         sign it.
     * @throws IOException If the pinned keys cannot be written.
     */
    public Answer follow(final String name, final String url, final @Nullable String signedBy,
            final boolean unverified, final boolean acceptRewrite) throws Refused, IOException {
        refuseReserved(name);
        refuseBoth(signedBy, unverified);
        // What is pinned now, put back if this follow does not end applied: pinned first and left, a key the person
        // was told "nothing was recorded" about stayed trusted on this machine.
        final Path signers = context.paths().projects().configurationSigners();
        final byte[] before = Files.exists(signers) ? Files.readAllBytes(signers) : null;
        try {
            return followPinned(name, url, signedBy, unverified, acceptRewrite);
        } catch (final Refused | IOException | RuntimeException ex) {
            restore(signers, before);
            throw ex;
        }
    }

    private Answer followPinned(final String name, final String url, final @Nullable String signedBy,
            final boolean unverified, final boolean acceptRewrite) throws Refused, IOException {
        final Path signers = context.paths().projects().configurationSigners();
        final byte[] before = Files.exists(signers) ? Files.readAllBytes(signers) : null;
        String pinned = "";
        if (signedBy != null && !SignedBy.isFingerprint(signedBy)) {
            final String line = line(name, signedBy);
            pin(line);
            pinned = String.valueOf(SignedBy.fingerprintOf(line.substring(line.indexOf(' ') + 1)));
        }
        final FollowedProjects projects = new FollowedProjects(context.paths().projects().followed());
        final boolean followedBefore = projects.find(name) != null;
        try {
            projects.follow(name, url, unverified);
        } catch (final IllegalArgumentException ex) {
            throw new Refused(String.valueOf(ex.getMessage()));
        }
        if (acceptRewrite) {
            // Forgetting what is in force is the whole of accepting: the next reconcile then has
            // nothing to descend from and applies what it verifies.
            projects.write(projects.require(name).forgettingWhatIsInForce());
        }
        Reconcile.Result result = new Reconcile(context).run(projects.require(name));
        if (signedBy != null && SignedBy.isFingerprint(signedBy)) {
            final String named = signedBy.strip();
            final String key;
            try {
                key = keyNamed(named, new Reconcile.Checked(result, SignedBy.keyOf(context.runner(),
                        context.paths().projects().followedClone(name), "FETCH_HEAD")));
            } catch (final Refused ex) {
                // A first follow the named key did not sign leaves nothing behind either.
                if (!followedBefore) {
                    projects.unfollow(name);
                    FollowedProjects.forget(context.paths().projects().followedClone(name));
                }
                throw ex;
            }
            if (key != null) {
                pin(line(name, key));
                pinned = named;
                result = new Reconcile(context).run(projects.require(name));
            }
        }
        final boolean applied = result.outcome() == Reconcile.Outcome.APPLIED
                || result.outcome() == Reconcile.Outcome.UNCHANGED;
        if (!applied) {
            restore(signers, before);
        }
        if (!followedBefore && !applied) {
            projects.unfollow(name);
            FollowedProjects.forget(context.paths().projects().followedClone(name));
            return new Answer(result, false, pinned);
        }
        projects.write(Reconcile.after(projects.require(name), result));
        return new Answer(result, true, pinned);
    }

    /**
     * Says what following would do with several keys, and records nothing.
     *
     * @param name The project's name.
     * @param url Its repository.
     * @param signers Whole public keys, as {@code ssh-ed25519 AAAA...}.
     * @return What following would do, with the fingerprints it would pin.
     * @throws Refused If one is not a whole public key.
     */
    public Answer check(final String name, final String url, final List<String> signers) throws Refused {
        refuseReserved(name);
        final List<String> lines = lines(name, signers);
        final FollowedProjects.Followed followed = new FollowedProjects.Followed(name, url,
                "", "", "", "", "", "", false);
        return new Answer(withKeys(name, lines, followed).result(), false, "", fingerprints(lines));
    }

    /**
     * Follows, pinning every key given - all of them or none - and reconciles once.
     *
     * @param name The project's name.
     * @param url Its repository.
     * @param signers Whole public keys, as {@code ssh-ed25519 AAAA...}.
     * @param acceptRewrite Whether a person accepted a history rewrite.
     * @return What the follow did, with the fingerprints pinned.
     * @throws Refused If one is not a whole public key; then nothing is pinned.
     * @throws IOException If the pinned keys cannot be written.
     */
    public Answer follow(final String name, final String url, final List<String> signers,
            final boolean acceptRewrite) throws Refused, IOException {
        refuseReserved(name);
        // Every key is read before any is pinned, so a list with one bad key pins nothing.
        final List<String> lines = lines(name, signers);
        for (final String line : lines) {
            pin(line);
        }
        final Answer one = follow(name, url, null, false, acceptRewrite);
        return new Answer(one.result(), one.recorded(), "", fingerprints(lines));
    }

    /**
     * Returns the request for one, several or no keys in the form a follow takes.
     * <p>
     * A fingerprint names one key, which is read out of the commit turned away; with several, only one of them can be
     * in that commit, so a list takes whole keys only.
     *
     * @param signedBy One key or fingerprint, or {@code null}.
     * @param signers Several whole keys, or {@code null}.
     * @param unverified Whether to follow without an anchor.
     * @throws Refused If the request says opposite things.
     */
    public static void refuseConflicting(final @Nullable String signedBy, final @Nullable List<String> signers,
            final boolean unverified) throws Refused {
        if (signers == null || signers.isEmpty()) {
            return;
        }
        if (signedBy != null) {
            throw new Refused("signedBy and signers say the same thing twice. Give the keys in one of them.");
        }
        if (unverified) {
            throw new Refused("--unverified and --signed-by say opposite things. Pick one.");
        }
        for (final String key : signers) {
            if (SignedBy.isFingerprint(key)) {
                throw new Refused("'" + key.strip() + "' is a fingerprint; with several keys, give each whole key"
                        + " ('ssh-ed25519 AAAA...'). A fingerprint names one key, read from the commit turned away.");
            }
        }
    }

    private static List<String> lines(final String name, final List<String> signers) throws Refused {
        final List<String> lines = new ArrayList<>();
        for (final String key : signers) {
            lines.add(line(name, key));
        }
        return lines;
    }

    private static List<String> fingerprints(final List<String> lines) {
        return lines.stream().map(line -> String.valueOf(SignedBy.fingerprintOf(line.substring(line.indexOf(' ') + 1))))
                .toList();
    }

    private static void refuseReserved(final String name) throws Refused {
        if (DefaultProject.is(name)) {
            throw new Refused(FollowedProjects.RESERVED);
        }
    }

    private static void refuseBoth(final @Nullable String signedBy, final boolean unverified)
            throws Refused {
        if (signedBy != null && unverified) {
            // Both is not a stricter setting, it is two different instructions. Refused rather
            // than one of them silently winning.
            throw new Refused("--unverified and --signed-by say opposite things. Pick one.");
        }
    }

    /**
     * Returns the key that signed what was found when its fingerprint is the one named, and
     * {@code null} when nothing signed was found or the pinned keys already accept it.
     *
     * @param named The fingerprint a person named.
     * @param found What a reconcile found, and the key it read.
     * @return The key to pin, or {@code null}.
     * @throws Refused If another key signed it, or its key could not be read.
     */
    private static @Nullable String keyNamed(final String named, final Reconcile.Checked found)
            throws Refused {
        final Reconcile.Result result = found.result();
        if (result.outcome() == Reconcile.Outcome.UNKNOWN_KEY || result.outcome() == Reconcile.Outcome.NO_ANCHOR) {
            // Read from the commit itself, not from the gate: with no key pinned on this machine at all the gate
            // answers NO_ANCHOR without verifying anything, so it names no signer - measured in a fresh account.
            final String key = found.key();
            if (key == null) {
                return null;
            }
            final String signer = SignedBy.fingerprintOf(key);
            if (!named.equals(signer)) {
                throw new Refused("signed by " + signer + ", not by " + named + ". Nothing was pinned.");
            }
            return key;
        }
        if (!result.signer().isEmpty() && !result.signer().equals(named)) {
            throw new Refused("signed by " + result.signer() + ", not by " + named + ". Nothing was pinned.");
        }
        return null;
    }

    private Reconcile.Checked withKey(final String name, final String line,
            final FollowedProjects.Followed followed) {
        return withKeys(name, List.of(line), followed);
    }

    private Reconcile.Checked withKeys(final String name, final List<String> added,
            final FollowedProjects.Followed followed) {
        Path scratch = null;
        try {
            scratch = Files.createTempFile("sokar-follow-signers-", "");
            final Path signers = context.paths().projects().configurationSigners();
            final List<String> lines = Files.isRegularFile(signers)
                    ? new ArrayList<>(Files.readAllLines(signers, StandardCharsets.UTF_8)) : new ArrayList<>();
            lines.addAll(added);
            Files.write(scratch, lines, StandardCharsets.UTF_8);
            return new Reconcile(context, scratch).checked(followed);
        } catch (final IOException ex) {
            return new Reconcile.Checked(new Reconcile.Result(Reconcile.Outcome.FAILED, "",
                    "cannot hold the named key for " + name + " to check with: " + ex.getMessage()), null);
        } finally {
            if (scratch != null) {
                try {
                    Files.deleteIfExists(scratch);
                } catch (final IOException ex) {
                    // A scratch file that will not go is not worth failing a check over.
                }
            }
        }
    }

    /**
     * Returns the pinned line for a key, labelled with the project it was named for.
     *
     * @param principal The label, which is not a check.
     * @param key The public key, as ssh-keygen prints it.
     * @return The line.
     * @throws Refused If it is not a public key.
     */
    private static String line(final String principal, final String key) throws Refused {
        final String[] fields = key.strip().split("\\s+");
        if (fields.length < 2) {
            throw new Refused("'" + key + "' is not a public key: expected 'ssh-ed25519 AAAA...'");
        }
        return principal + " " + fields[0] + " " + fields[1];
    }

    /**
     * Writes one line into this machine's pinned signers. Appended rather than replacing the file:
     * a machine follows several projects and they need not share a key. A line already there is
     * not written twice.
     */
    /** Puts the pinned keys back as they were, or removes the file there was none before. */
    private static void restore(final Path signers, final byte @Nullable [] before) throws IOException {
        if (before == null) {
            Files.deleteIfExists(signers);
        } else {
            Files.write(signers, before);
        }
    }

    private void pin(final String line) throws IOException {
        final Path signers = context.paths().projects().configurationSigners();
        Files.createDirectories(signers.getParent());
        final List<String> lines = Files.exists(signers)
                ? new ArrayList<>(Files.readAllLines(signers, StandardCharsets.UTF_8)) : new ArrayList<>();
        if (!lines.contains(line)) {
            lines.add(line);
        }
        Files.writeString(signers, String.join("\n", lines) + "\n");
    }
}
