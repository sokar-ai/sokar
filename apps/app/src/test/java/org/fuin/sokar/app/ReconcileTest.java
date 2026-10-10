package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link Reconcile}, against real git and real signatures.
 * <p>
 * The property worth measuring is the order: a machine handed something it cannot verify must keep
 * running what it verified last. A fake runner would let that pass without the working tree ever
 * having been at risk.
 */
class ReconcileTest {

    private final CommandRunner runner = new ProcessCommandRunner();

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private void git(final Path where, final String... arguments) {
        final java.util.List<String> all = new java.util.ArrayList<>(
                java.util.List.of("git", "-C", where.toString()));
        all.addAll(java.util.List.of(arguments));
        final var result = runner.run(Command.of(all));
        assertThat(result.exitCode()).as(String.join(" ", all) + " -> " + result.standardError())
                .isZero();
    }

    private Path key(final Path dir, final String name) {
        runner.run(Command.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", name,
                "-f", dir.resolve(name).toString()));
        return dir.resolve(name);
    }

    private void pin(final SokarContext context, final String principal, final Path key)
            throws IOException {
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split("\\s+");
        Files.createDirectories(context.paths().projects().configurationSigners().getParent());
        Files.writeString(context.paths().projects().configurationSigners(),
                principal + " " + fields[0] + " " + fields[1] + "\n");
    }

    /** A project repository somebody else publishes, with one commit. */
    private Path published(final Path dir, final Path signingKey, final String project)
            throws IOException {
        final Path repo = dir.resolve("published");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        commit(repo, signingKey, project);
        return repo;
    }

    private void commit(final Path repo, final Path signingKey, final String project)
            throws IOException {
        commit(repo, signingKey, project, "Followed from a repository");
    }

    /**
     * Commits a project file whose description says which version of it this is.
     *
     * <p>The NAME cannot be used to tell two commits apart any more: a file that calls itself
     * something other than the name it is followed under is refused, because the machine would
     * then hold a project whose containers and mirrors are named after the file and a follow
     * record that resolves to none of them.
     */
    private void commit(final Path repo, final Path signingKey, final String project,
            final String description) throws IOException {
        Files.writeString(repo.resolve("project.yml"), """
                project:
                  name: %s
                  description: %s
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                """.formatted(project, description));
        git(repo, "add", "project.yml");
        if (signingKey == null) {
            git(repo, "commit", "-q", "--no-gpg-sign", "-m", "configuration");
        } else {
            git(repo, "config", "gpg.format", "ssh");
            git(repo, "config", "user.signingkey", signingKey.toString());
            git(repo, "commit", "-q", "-S", "-m", "configuration");
        }
    }

    /** Commits a project file that names whose commits change it, signed by one key. */
    private void commitNaming(final Path repo, final Path signingKey, final String description,
            final Path... signers) throws IOException {
        final StringBuilder named = new StringBuilder();
        for (final Path signer : signers) {
            named.append("    - \"").append(Files.readString(Path.of(signer + ".pub")).strip()).append("\"\n");
        }
        Files.writeString(repo.resolve("project.yml"), """
                project:
                  name: demo
                  description: %s
                  security_class: guarded
                  signers:
                %simage:
                  base_image: ubuntu:24.04
                """.formatted(description, named));
        git(repo, "add", "project.yml");
        git(repo, "config", "user.signingkey", signingKey.toString());
        git(repo, "commit", "-q", "-S", "-m", description);
    }

    private static String typeAndKey(final Path key) throws IOException {
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split("\\s+");
        return fields[0] + " " + fields[1];
    }

    @Test
    void a_pinned_key_hands_on_to_the_key_its_commit_adds(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        pin(context, "demo", old);
        final Path repo = published(dir, old, "demo");
        final Reconcile.Result first = new Reconcile(context).run(followed(repo));
        assertThat(first.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);

        commitNaming(repo, old, "handed on", old, successor);
        final Reconcile.Result handed = new Reconcile(context).run(Reconcile.after(followed(repo), first));

        assertThat(handed.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(handed.detail()).contains("pinned " + SignedBy.fingerprintOf(typeAndKey(successor)));
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .contains("demo " + typeAndKey(successor)).contains("demo " + typeAndKey(old));

        commitNaming(repo, successor, "the old one retired", successor);
        final Reconcile.Result retired = new Reconcile(context).run(Reconcile.after(followed(repo), handed));

        assertThat(retired.outcome()).as("signed by the successor alone").isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(retired.detail()).contains("unpinned " + SignedBy.fingerprintOf(typeAndKey(old)));
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .doesNotContain(typeAndKey(old));

        commit(repo, old, "demo", "signed by the key that was retired");
        assertThat(new Reconcile(context).run(Reconcile.after(followed(repo), retired)).outcome())
                .isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
    }

    @Test
    void a_key_never_vouches_for_the_commit_that_adds_it(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path old = key(dir, "old");
        final Path stranger = key(dir, "stranger");
        pin(context, "demo", old);
        final Path repo = published(dir, old, "demo");
        final Reconcile.Result first = new Reconcile(context).run(followed(repo));

        commitNaming(repo, stranger, "added by itself", old, stranger);
        final Reconcile.Result result = new Reconcile(context).run(Reconcile.after(followed(repo), first));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        assertThat(result.detail()).contains("changes project.signers");
        assertThat(result.commit()).as("what runs is still what was verified").isEqualTo(first.commit());
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .doesNotContain(typeAndKey(stranger));
    }

    @Test
    void a_key_added_on_the_way_is_followed_through_to_the_commit_fetched(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path old = key(dir, "old");
        final Path successor = key(dir, "successor");
        pin(context, "demo", old);
        final Path repo = published(dir, old, "demo");
        final Reconcile.Result first = new Reconcile(context).run(followed(repo));

        // Both arrive in one fetch: the hand-on, then a commit only the successor signed.
        commitNaming(repo, old, "handed on", successor);
        commit(repo, successor, "demo", "signed by the successor");
        final Reconcile.Result result = new Reconcile(context).run(Reconcile.after(followed(repo), first));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(Files.readString(context.paths().projects().configurationSigners()))
                .contains("demo " + typeAndKey(successor)).doesNotContain(typeAndKey(old));
    }

    private FollowedProjects.Followed followed(final Path repo) {
        return new FollowedProjects.Followed("demo", repo.toString(), "", "", "", "");
    }

    @Test
    void a_fingerprint_named_on_a_machine_with_nothing_pinned_pins_the_key_that_signed_it(@TempDir final Path dir)
            throws Exception {
        // Measured in a fresh account: with no key pinned at all the gate verifies nothing and names no signer, so
        // a fingerprint was matched against nothing and the follow stayed NO_ANCHOR.
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, "demo");
        final String fingerprint = SignedBy.fingerprintOf(Files.readString(Path.of(key + ".pub")).strip());
        assertThat(context.paths().projects().configurationSigners()).doesNotExist();
        final FollowSignedBy following = new FollowSignedBy(context);

        assertThat(following.check("demo", repo.toString(), fingerprint, false).result().outcome())
                .isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(context.paths().projects().configurationSigners()).as("a check pins nothing").doesNotExist();
        final FollowSignedBy.Answer answer = following.follow("demo", repo.toString(), fingerprint, false, false);

        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(answer.pinned()).isEqualTo(fingerprint);
        assertThat(answer.result().signer()).isEqualTo(fingerprint);
    }

    @Test
    void a_fingerprint_that_did_not_sign_it_pins_nothing_and_follows_nothing(@TempDir final Path dir)
            throws Exception {
        final SokarContext context = context(dir);
        final Path repo = published(dir, key(dir, "operator"), "demo");
        final String other = SignedBy.fingerprintOf(Files.readString(Path.of(key(dir, "other") + ".pub")).strip());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new FollowSignedBy(context).follow("demo", repo.toString(), other, false, false))
                .isInstanceOf(FollowSignedBy.Refused.class).hasMessageContaining("not by " + other);
        assertThat(context.paths().projects().configurationSigners()).doesNotExist();
        assertThat(new FollowedProjects(context.paths().projects().followed()).find("demo")).isNull();
    }

    @Test
    void a_follow_with_every_key_of_a_person_accepts_a_commit_signed_by_any_of_them(@TempDir final Path dir)
            throws Exception {
        // A person who signs from two computers has two keys at the forge; pinning one refused the other's commit.
        final SokarContext context = context(dir);
        final Path laptop = key(dir, "laptop");
        final Path desktop = key(dir, "desktop");
        final Path repo = published(dir, laptop, "demo");
        final java.util.List<String> keys = java.util.List.of(
                Files.readString(Path.of(laptop + ".pub")).strip(), Files.readString(Path.of(desktop + ".pub")).strip());
        final java.util.List<String> fingerprints = keys.stream().map(SignedBy::fingerprintOf).toList();
        final FollowSignedBy following = new FollowSignedBy(context);

        final FollowSignedBy.Answer would = following.check("demo", repo.toString(), keys);
        assertThat(would.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(would.signers()).as("what it would pin").isEqualTo(fingerprints);
        assertThat(context.paths().projects().configurationSigners()).as("a check pins nothing").doesNotExist();

        final FollowSignedBy.Answer answer = following.follow("demo", repo.toString(), keys, false);
        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(answer.signers()).isEqualTo(fingerprints);
        assertThat(PinnedSigners.of(context.paths().projects().configurationSigners())).containsEntry("demo", fingerprints);

        // The other computer's commit is accepted too.
        commit(repo, desktop, "demo", "signed on the other computer");
        final Reconcile.Result next = new Reconcile(context)
                .run(new FollowedProjects(context.paths().projects().followed()).require("demo"));
        assertThat(next.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(next.signer()).isEqualTo(fingerprints.get(1));

        // A third key's is not.
        commit(repo, key(dir, "stranger"), "demo", "signed by nobody named");
        assertThat(new Reconcile(context).run(new FollowedProjects(context.paths().projects().followed()).require("demo"))
                .outcome()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
    }

    @Test
    void a_list_with_one_key_that_is_not_one_pins_none_of_them(@TempDir final Path dir) throws Exception {
        final SokarContext context = context(dir);
        final Path laptop = key(dir, "laptop");
        final Path repo = published(dir, laptop, "demo");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FollowSignedBy(context).follow("demo",
                repo.toString(), java.util.List.of(Files.readString(Path.of(laptop + ".pub")).strip(), "nonsense"),
                false)).isInstanceOf(FollowSignedBy.Refused.class).hasMessageContaining("not a public key");
        assertThat(context.paths().projects().configurationSigners()).doesNotExist();
        assertThat(new FollowedProjects(context.paths().projects().followed()).find("demo")).isNull();
    }

    @Test
    void several_keys_are_refused_beside_one_key_beside_unverified_and_as_fingerprints() {
        final java.util.List<String> two = java.util.List.of("ssh-ed25519 AAAA1", "ssh-ed25519 AAAA2");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> FollowSignedBy.refuseConflicting("ssh-ed25519 AAAA0", two, false))
                .isInstanceOf(FollowSignedBy.Refused.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FollowSignedBy.refuseConflicting(null, two, true))
                .isInstanceOf(FollowSignedBy.Refused.class).hasMessageContaining("opposite");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FollowSignedBy.refuseConflicting(null,
                java.util.List.of("SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"), false))
                .isInstanceOf(FollowSignedBy.Refused.class).hasMessageContaining("fingerprint");
        org.assertj.core.api.Assertions.assertThatCode(() -> FollowSignedBy.refuseConflicting("x", null, true))
                .as("one key alone is the other method's to judge").doesNotThrowAnyException();
    }

    @Test
    void applies_a_commit_the_pinned_key_signed(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");

        final Reconcile.Result result = new Reconcile(context).run(followed(repo));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.commit()).hasSize(40);
        assertThat(context.paths().projects().followedClone("demo").resolve("project.yml")).exists();
        // What a person compares with the key they were given, beside it.
        assertThat(result.signer()).as("the fingerprint of the key that signed what is in force")
                .isEqualTo(SignedBy.fingerprintOf(Files.readString(Path.of(key + ".pub")).strip()));
    }

    /**
     * The order this class exists for: what cannot be verified never reaches the working tree.
     */
    @Test
    void keeps_what_it_verified_when_the_next_commit_is_unsigned(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first = new Reconcile(context).run(followed(repo));
        final FollowedProjects.Followed after = Reconcile.after(followed(repo), first);

        // The project's repository moves on, unsigned.
        Files.writeString(repo.resolve("project.yml"), """
                project:
                  name: demo
                  description: Changed by somebody who did not sign
                  security_class: online
                  upstream: git@example.org:demo.git
                image:
                  base_image: ubuntu:24.04
                """);
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "unsigned change");

        final Reconcile.Result second = new Reconcile(context).run(after);

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        assertThat(second.commit()).as("what runs is still what was verified")
                .isEqualTo(first.commit());
        assertThat(Files.readString(context.paths().projects().followedClone("demo").resolve("project.yml")))
                .as("the working tree never moved").doesNotContain("online");
    }

    @Test
    void a_second_run_with_nothing_new_changes_nothing(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile reconcile = new Reconcile(context);
        final Reconcile.Result first = reconcile.run(followed(repo));

        final Reconcile.Result second = reconcile.run(Reconcile.after(followed(repo), first));

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(second.commit()).isEqualTo(first.commit());
        assertThat(second.signer()).startsWith("SHA256:").isEqualTo(first.signer());
    }

    @Test
    void a_repository_that_cannot_be_reached_leaves_everything_alone(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        pin(context, "demo", key(dir, "operator"));

        final Reconcile.Result result = new Reconcile(context).run(
                new FollowedProjects.Followed("demo", dir.resolve("nowhere").toString(),
                        "", "", "", ""));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNREACHABLE);
        assertThat(result.detail()).contains("cannot fetch");
    }

    /** A machine with nothing pinned verifies nothing, so it applies nothing. */
    @Test
    void a_machine_with_no_anchor_applies_nothing(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path repo = published(dir, key(dir, "operator"), "demo");

        final Reconcile.Result result = new Reconcile(context).run(followed(repo));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.NO_ANCHOR);
        assertThat(result.detail()).contains("no key is pinned");
    }

    /**
     * Signed by the right key and still not a project: that is a different problem from an
     * unsigned commit, and only one of the two is about trust.
     */
    @Test
    void a_signed_commit_that_is_not_a_project_is_its_own_outcome(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        Files.writeString(repo.resolve("project.yml"), "project:\n  name: NOT A NAME\n");
        git(repo, "add", "project.yml");
        git(repo, "config", "gpg.format", "ssh");
        git(repo, "config", "user.signingkey", key.toString());
        git(repo, "commit", "-q", "-S", "-m", "signed nonsense");

        final Reconcile.Result result = new Reconcile(context).run(followed(repo));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNUSABLE);
        assertThat(result.detail()).contains("does not read as a project");
    }

    /**
     * A signed commit that is not a descendant of the one in force. Somebody with the key may have
     * rebased - and somebody without it may be serving an older signed configuration to put back a
     * rule that was taken away, which needs no key at all. The two are identical from here.
     */
    @Test
    void refuses_a_signed_commit_that_did_not_come_after_the_one_in_force(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile reconcile = new Reconcile(context);
        final Reconcile.Result first = reconcile.run(followed(repo));
        assertThat(first.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);

        // The published repository's history is replaced: a new root commit, properly signed, and
        // with different content - an identical tree and message with no parent would be the same
        // commit, because git names a commit by what is in it.
        git(repo, "checkout", "-q", "--orphan", "rewritten");
        commit(repo, key, "demo", "rewritten");
        git(repo, "branch", "-q", "-M", "rewritten", "main");

        final Reconcile.Result second = reconcile.run(Reconcile.after(followed(repo), first));

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.REWRITTEN);
        assertThat(second.commit()).as("what runs is still what was verified")
                .isEqualTo(first.commit());
        assertThat(second.detail()).contains("--accept-rewrite");
    }

    /** Forgetting what is in force is the whole of accepting it. */
    @Test
    void accepting_a_rewrite_is_forgetting_what_was_in_force(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile reconcile = new Reconcile(context);
        reconcile.run(followed(repo));
        git(repo, "checkout", "-q", "--orphan", "rewritten");
        commit(repo, key, "demo", "rewritten");
        git(repo, "branch", "-q", "-M", "rewritten", "main");

        // followed(repo) carries no commit, which is what --accept-rewrite leaves behind.
        final Reconcile.Result accepted = reconcile.run(followed(repo));

        assertThat(accepted.outcome()).as(accepted.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    // ---------------------------------------------------------------- what a reconciliation may
    // touch, and what it may not. What a reconciliation may
    // release a held message or disturb a task is not reconciliation, it is remote control of
    // somebody's machine by whoever can commit. Asserted rather than assumed - the property holds
    // because Reconcile only ever moves the followed clone, and that is exactly the kind of thing
    // that stays true until somebody adds one line.

    @Test
    void what_a_person_held_survives_a_reconciliation(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");

        // A held message, the moderation file that holds a peer, and a running task's own state.
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox("sokar-demo-shell"));
        mailbox.create();
        final Path held = mailbox.hold().resolve("m1.json");
        Files.writeString(held, "{\"held\":\"by a person\"}");
        final Path mode = mailbox.root().resolve("moderation.json");
        Files.writeString(mode, "{\"reviewer\":\"prompt\"}");
        final Path taskState = context.paths().tasks().containerState("sokar-demo-shell");
        Files.createDirectories(taskState);
        Files.writeString(taskState.resolve("gate.pid"), "4242");

        assertThat(new Reconcile(context).run(followed(repo)).outcome())
                .isEqualTo(Reconcile.Outcome.APPLIED);

        assertThat(held).exists().content().isEqualTo("{\"held\":\"by a person\"}");
        assertThat(mode).exists().content().isEqualTo("{\"reviewer\":\"prompt\"}");
        assertThat(taskState.resolve("gate.pid")).exists().content().isEqualTo("4242");
    }

    @Test
    void a_local_edit_to_a_reconciled_file_is_replaced_and_reported(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first = new Reconcile(context).run(followed(repo));
        final Path clone = context.paths().projects().followedClone("demo");

        // Somebody edits the reconciled file on the machine, and drops a file of their own beside
        // it. The repository wins for what it covers - and says so, rather than doing it quietly.
        Files.writeString(clone.resolve("project.yml"), "project:\n  name: edited-by-hand\n");
        Files.writeString(clone.resolve("notes.txt"), "mine");

        commit(repo, key, "demo", "moved on");
        final Reconcile.Result second =
                new Reconcile(context).run(Reconcile.after(followed(repo), first));

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(clone.resolve("project.yml")).content().contains("moved on");
        // Reported. Without this the rule is real and invisible, which the rule refused to leave unsaid.
        assertThat(second.detail()).contains("replaced").contains("project.yml");
        // And a file the repository does not cover is left where it is.
        assertThat(clone.resolve("notes.txt")).exists().content().isEqualTo("mine");
        assertThat(second.detail()).doesNotContain("notes.txt");
    }

    @Test
    void two_machines_at_the_same_commit_end_up_with_the_same_project(@TempDir final Path dir)
            throws IOException {
        final Path key = key(dir, "operator");
        final Path repo = published(dir, key, "demo");

        // Two accounts, two machines as far as this is concerned: separate XDG roots, nothing
        // copied between them, the same repository and the same pinned key.
        final SokarContext one = context(dir.resolve("machine-one"));
        final SokarContext two = context(dir.resolve("machine-two"));
        pin(one, "demo", key);
        pin(two, "demo", key);

        final Reconcile.Result first = new Reconcile(one).run(followed(repo));
        final Reconcile.Result second = new Reconcile(two).run(followed(repo));

        assertThat(first.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(first.commit()).isEqualTo(second.commit());
        assertThat(Files.readString(one.paths().projects().followedClone("demo").resolve("project.yml")))
                .isEqualTo(Files.readString(
                        two.paths().projects().followedClone("demo").resolve("project.yml")));
    }

    @Test
    void a_key_this_machine_was_never_given_is_its_own_outcome(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        pin(context, "demo", key(dir, "operator"));

        // Signed, and signed well - by somebody else's key. This is the case that belongs in front
        // of a person: either a key that has moved, or somebody putting a project file past the
        // machine. Reported as one refusal with an unsigned commit, the two were the same event.
        final Path stranger = key(dir, "stranger");
        final Path repo = published(dir, stranger, "demo");

        final Reconcile.Result result = new Reconcile(context).run(followed(repo));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNKNOWN_KEY);
        // The commit turned away, which used to be discarded - the record kept only what was in
        // force, so nothing could say what had been rejected.
        assertThat(result.refused()).hasSize(40);
        assertThat(result.commit()).isEmpty();
        // And the fingerprint, so a person can compare it with the key they meant to pin.
        assertThat(result.signer()).startsWith("SHA256:");
        assertThat(result.needsAPerson()).isTrue();
    }

    @Test
    void an_unsigned_commit_names_neither_a_key_nor_a_person_to_blame(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        pin(context, "demo", key(dir, "operator"));
        final Path repo = published(dir, null, "demo");

        final Reconcile.Result result = new Reconcile(context).run(followed(repo));

        // Different outcome from the one above, which is the whole point: somebody forgot to sign
        // is not somebody signing with a key they should not have.
        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.NOT_SIGNED);
        assertThat(result.refused()).hasSize(40);
        assertThat(result.signer()).isEmpty();
    }

    @Test
    void a_machine_with_no_vault_is_unreachable_rather_than_locked(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        pin(context, "demo", key(dir, "operator"));

        // There is no vault here at all, so nothing is locked away and nothing about one explains
        // the failed fetch. Saying "unlock your vault" would send somebody to a vault they have
        // not made.
        final Reconcile.Result result = new Reconcile(context).run(
                new FollowedProjects.Followed("demo", dir.resolve("nowhere").toString(),
                        "", "", "", ""));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNREACHABLE);
        assertThat(result.detail()).doesNotContain("vault");
    }

    @Test
    void an_unverified_follow_applies_what_nobody_signed(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        // Nothing pinned, and the commit is unsigned - which is NOT_SIGNED for an ordinary follow.
        final Path repo = published(dir, null, "demo");

        final Reconcile.Result result = new Reconcile(context).run(
                new FollowedProjects.Followed("demo", repo.toString(), "", "", "", "",
                        "", "", true));

        // Somebody asked for this. The check is skipped; the reporting is not.
        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.commit()).hasSize(40);
        assertThat(context.paths().projects().followedClone("demo").resolve("project.yml")).exists();
    }

    @Test
    void an_unverified_follow_still_refuses_a_file_that_is_not_a_project(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path repo = dir.resolve("published");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        Files.writeString(repo.resolve("project.yml"), "this: is not a project\n");
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "broken");

        final Reconcile.Result result = new Reconcile(context).run(
                new FollowedProjects.Followed("demo", repo.toString(), "", "", "", "",
                        "", "", true));

        // Unverified means nobody checked WHO wrote it - not that anything goes. A file that is
        // not a project would fail at the first task start, which is somewhere else and later.
        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNUSABLE);
    }

    @Test
    void following_unverified_is_per_project(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path signed = published(dir, key, "demo");

        final Reconcile.Result verified = new Reconcile(context).run(followed(signed));
        // A second repository of its own, because a project file that calls itself something
        // other than the name it is followed under is refused.
        final Path other = dir.resolve("published-two");
        Files.createDirectories(other);
        git(other, "init", "-q", "-b", "main", ".");
        git(other, "config", "user.email", "operator@example.org");
        git(other, "config", "user.name", "Operator");
        commit(other, null, "demo-two");

        final Reconcile.Result without = new Reconcile(context).run(
                new FollowedProjects.Followed("demo-two", other.toString(), "", "", "", "",
                        "", "", true));

        // One project without an anchor must not quieten another that has one: they are separate
        // records and separate answers.
        assertThat(verified.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(without.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @Test
    void a_declared_repository_need_not_exist_for_the_follow_to_apply(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path repo = dir.resolve("published");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        // A second repository pointing at a forge nobody can reach from here.
        Files.writeString(repo.resolve("project.yml"), """
                project:
                  name: demo
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                repositories:
                  backend:
                    upstream: "git@nowhere.invalid:acme/backend.git"
                """);
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "two repositories");

        final Reconcile.Result result = new Reconcile(context).run(
                new FollowedProjects.Followed("demo", repo.toString(), "", "", "", "",
                        "", "", true));

        // Following reads the project file and checks what THIS machine can answer about it - the
        // egress sets it has, the name it is followed under. It reaches no work repository: a
        // mirror is made when a task is started for one, and a project may name a repository that
        // does not exist yet, which is what a project still being planned looks like.
        assertThat(result.outcome()).as(result.detail()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(org.fuin.sokar.core.project.ProjectReader.read(
                        context.paths().projects().followedClone("demo").resolve("project.yml"))
                .repositoryNames()).containsExactly("demo", "backend");
    }

    @Test
    void an_unverified_follow_is_still_unverified_in_the_record_it_writes(@TempDir final Path dir)
            throws IOException {

        // Where it was lost. Every follow ends by writing 'after(known, result)', and that method
        // rebuilt the record through a constructor that defaults the flag - so the follow wrote
        // "unverified" and the next line wrote it away again. The outcome was right, the clone was
        // right, and the one field that says WHO DECIDES read as though a signature had been
        // checked. Found through the interface, whose dialog shows that field on every project.
        final SokarContext context = context(dir);
        final Path repo = published(dir, null, "demo");
        final FollowedProjects.Followed taken = new FollowedProjects.Followed("demo",
                repo.toString(), "", "", "", "", "", "", true);

        final FollowedProjects.Followed written =
                Reconcile.after(taken, new Reconcile(context).run(taken));

        assertThat(written.outcome()).isEqualTo("APPLIED");
        assertThat(written.unverified()).isTrue();
    }

    @Test
    void accepting_a_rewrite_forgets_the_commit_and_nothing_else(@TempDir final Path dir)
            throws IOException {

        // The same field, lost the same way in the other two writers: both rebuilt the record by
        // hand to clear the commit. Accepting a rewrite must not quietly promote an unverified
        // follow to one that claims a signature was checked.
        final FollowedProjects projects = new FollowedProjects(dir.resolve("followed"));
        projects.write(new FollowedProjects.Followed("demo", "git@example.com:x/demo.git",
                "a1b2c3", "2026-09-19T00:00:00Z", "REWRITTEN", "history was rewritten",
                "d4e5f6", "SHA256:whoever", true));

        projects.write(projects.find("demo").forgettingWhatIsInForce());

        final FollowedProjects.Followed after = projects.find("demo");
        assertThat(after.commit()).isEmpty();
        assertThat(after.unverified()).isTrue();
        assertThat(after.url()).isEqualTo("git@example.com:x/demo.git");
        assertThat(after.refused()).isEqualTo("d4e5f6");
        assertThat(after.signer()).isEqualTo("SHA256:whoever");
    }

    @Test
    void a_check_says_what_following_would_do_and_does_none_of_it(@TempDir final Path dir)
            throws IOException {

        // Asked before anything is written. A follow that cannot apply used to leave a project
        // behind that was followed with nothing in force - one was met, with no verb
        // to take it away.
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "demo", key);
        final Path repo = published(dir, key, "demo");

        final Reconcile.Result would = new Reconcile(context).check(followed(repo));

        assertThat(would.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(would.commit()).hasSize(40);
        // And nothing of this account moved: no clone, and nothing recorded.
        assertThat(context.paths().projects().followedClone("demo")).doesNotExist();
    }

    @Test
    void a_check_gives_the_same_refusal_the_follow_would(@TempDir final Path dir)
            throws IOException {

        final SokarContext context = context(dir);
        // Nothing pinned and nothing signed. What it answers matters less than that it answers
        // the SAME as the follow: a check that disagreed with the thing it stands in for would
        // send somebody to follow a project that then refuses, or stop them following one that
        // would have worked.
        final Path repo = published(dir, null, "demo");

        final Reconcile.Result would = new Reconcile(context).check(followed(repo));

        assertThat(would.needsAPerson()).isTrue();
        // Nothing of this account moved while it was asked.
        assertThat(context.paths().projects().followedClone("demo")).doesNotExist();

        final Reconcile.Result real = new Reconcile(context).run(followed(repo));
        assertThat(would.outcome()).isEqualTo(real.outcome());
        assertThat(would.detail()).isEqualTo(real.detail());
    }

    @Test
    void a_repository_only_a_key_opens_says_the_key_is_missing_not_the_network(
            @TempDir final Path dir) {

        // The refusal the operator was given was "unlock your vault", for a vault that held
        // nothing this path would have used - because the follow never asked the vault for
        // anything. With no key stored, an ssh URL that cannot be fetched is a missing
        // credential, and the answer names the entry and the command that fills it.
        final SokarContext context = context(dir);

        final Reconcile.Result result = new Reconcile(context).run(
                new FollowedProjects.Followed("demo", "git@nowhere.invalid:acme/demo.git",
                        "", "", "", ""));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.NO_CREDENTIAL);
        // Named by host, so a company forge and a public one are two credentials rather than
        // one key offered to whatever host a project file happens to name.
        assertThat(result.detail()).contains("sokar vault put git.ssh.nowhere.invalid");
        assertThat(result.needsAPerson()).isTrue();
    }

    @Test
    void a_local_repository_that_cannot_be_fetched_is_not_blamed_on_a_missing_key(
            @TempDir final Path dir) {

        // The other half of the same rule. A path is not opened by an ssh key, so sending
        // somebody to store one would waste their time on a URL that is simply wrong.
        final SokarContext context = context(dir);

        final Reconcile.Result result = new Reconcile(context).run(
                new FollowedProjects.Followed("demo", dir.resolve("nowhere").toString(),
                        "", "", "", ""));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.UNREACHABLE);
    }

    @Test
    void aWholeKeyThatDidNotSignItIsNotLeftPinnedWhenTheFollowFails(@TempDir final Path dir) throws Exception {

        // The key was pinned before anything was checked and stayed when the follow failed - "nothing was recorded",
        // the answer said, while the key stayed trusted on this machine.
        final SokarContext context = context(dir);
        final Path repo = published(dir, key(dir, "operator"), "demo");
        final String other = Files.readString(Path.of(key(dir, "other") + ".pub")).strip();

        final FollowSignedBy.Answer answer = new FollowSignedBy(context).follow("demo", repo.toString(), other, false,
                false);

        assertThat(answer.recorded()).isFalse();
        assertThat(context.paths().projects().configurationSigners().toFile().exists()
                ? Files.readString(context.paths().projects().configurationSigners()) : "")
                .doesNotContain(other.split("\\s+")[1]);
    }

    /** A project repository whose file says the class, unsigned: followed with --unverified in these tests. */
    private Path ofClass(final Path dir, final String securityClass) throws IOException {
        final Path repo = Files.createDirectories(dir.resolve("published-" + securityClass));
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "operator@example.org");
        git(repo, "config", "user.name", "Operator");
        setClass(repo, securityClass);
        return repo;
    }

    private void setClass(final Path repo, final String securityClass) throws IOException {
        Files.writeString(repo.resolve("project.yml"), """
                project:
                  name: demo
                  description: %s
                  security_class: %s
                image:
                  base_image: ubuntu:24.04
                """.formatted(securityClass + " " + System.nanoTime(), securityClass));
        git(repo, "add", "project.yml");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", securityClass);
    }

    @Test
    void an_offline_project_is_followed_from_a_local_directory(@TempDir final Path dir) throws Exception {
        final SokarContext context = context(dir);
        final Path repo = ofClass(dir, "offline");

        final FollowSignedBy.Answer answer = new FollowSignedBy(context).follow("demo", repo.toString(), null, true,
                false);

        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @Test
    void an_offline_project_is_followed_from_a_bundle(@TempDir final Path dir) throws Exception {
        final SokarContext context = context(dir);
        final Path repo = ofClass(dir, "offline");
        final Path bundle = dir.resolve("demo.bundle");
        git(repo, "bundle", "create", "-q", bundle.toString(), "--all");

        final FollowSignedBy.Answer answer = new FollowSignedBy(context).follow("demo", bundle.toString(), null, true,
                false);

        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
    }

    @Test
    void an_offline_project_is_not_followed_from_a_url(@TempDir final Path dir) throws Exception {
        // Following fetches the definition again in the background: an offline project would connect every round.
        final SokarContext context = context(dir);
        final Path repo = ofClass(dir, "offline");

        final FollowSignedBy.Answer answer = new FollowSignedBy(context).follow("demo", repo.toUri().toString(), null,
                true, false);

        assertThat(answer.result().outcome()).isEqualTo(Reconcile.Outcome.OFFLINE_FROM_A_URL);
        assertThat(answer.result().detail()).contains("follow it from a file");
        assertThat(answer.recorded()).isFalse();
    }

    @Test
    void a_project_followed_from_a_url_that_turns_offline_keeps_what_it_had(@TempDir final Path dir)
            throws Exception {
        final SokarContext context = context(dir);
        final Path repo = ofClass(dir, "guarded");
        final FollowSignedBy.Answer first = new FollowSignedBy(context).follow("demo", repo.toUri().toString(), null,
                true, false);
        assertThat(first.result().outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        setClass(repo, "offline");

        final java.util.Map<String, Reconcile.Result> done = new ConfigurationWatch(context, java.time.Duration.ZERO)
                .refresh("demo");

        assertThat(done.get("demo").outcome()).isEqualTo(Reconcile.Outcome.OFFLINE_FROM_A_URL);
        assertThat(done.get("demo").commit()).as("what was in force stays").isEqualTo(first.result().commit());
    }

    @Test
    void a_project_followed_from_a_file_is_never_fetched_and_says_how_it_changes(@TempDir final Path dir)
            throws Exception {
        final SokarContext context = context(dir);
        final Path repo = ofClass(dir, "offline");
        final FollowSignedBy.Answer first = new FollowSignedBy(context).follow("demo", repo.toString(), null, true,
                false);
        setClass(repo, "offline");

        final java.util.Map<String, Reconcile.Result> done = new ConfigurationWatch(context, java.time.Duration.ZERO)
                .refresh("demo");

        assertThat(done.get("demo").outcome()).isEqualTo(Reconcile.Outcome.FROM_A_FILE);
        assertThat(done.get("demo").commit()).isEqualTo(first.result().commit());
        assertThat(done.get("demo").detail()).contains("follow it again from a newer file");
    }
}
