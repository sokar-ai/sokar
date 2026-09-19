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
        Files.createDirectories(context.paths().configurationSigners().getParent());
        Files.writeString(context.paths().configurationSigners(),
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

    private FollowedProjects.Followed followed(final Path repo) {
        return new FollowedProjects.Followed("demo", repo.toString(), "", "", "", "");
    }

    @Test
    void applies_a_commit_the_pinned_key_signed(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "operator", key);
        final Path repo = published(dir, key, "demo");

        final Reconcile.Result result = new Reconcile(context).run(followed(repo));

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(result.commit()).hasSize(40);
        assertThat(context.paths().followedClone("demo").resolve("project.yml")).exists();
    }

    /**
     * The order this class exists for: what cannot be verified never reaches the working tree.
     */
    @Test
    void keeps_what_it_verified_when_the_next_commit_is_unsigned(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "operator", key);
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
        assertThat(Files.readString(context.paths().followedClone("demo").resolve("project.yml")))
                .as("the working tree never moved").doesNotContain("online");
    }

    @Test
    void a_second_run_with_nothing_new_changes_nothing(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path key = key(dir, "operator");
        pin(context, "operator", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile reconcile = new Reconcile(context);
        final Reconcile.Result first = reconcile.run(followed(repo));

        final Reconcile.Result second = reconcile.run(Reconcile.after(followed(repo), first));

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.UNCHANGED);
        assertThat(second.commit()).isEqualTo(first.commit());
    }

    @Test
    void a_repository_that_cannot_be_reached_leaves_everything_alone(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        pin(context, "operator", key(dir, "operator"));

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
        pin(context, "operator", key);
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
        pin(context, "operator", key);
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
        pin(context, "operator", key);
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
        pin(context, "operator", key);
        final Path repo = published(dir, key, "demo");

        // A held message, the moderation file that holds a peer, and a running task's own state.
        final Mailbox mailbox = new Mailbox(context.paths().mailbox("sokar-demo-shell"));
        mailbox.create();
        final Path held = mailbox.hold().resolve("m1.json");
        Files.writeString(held, "{\"held\":\"by a person\"}");
        final Path mode = mailbox.root().resolve("moderation.json");
        Files.writeString(mode, "{\"reviewer\":\"prompt\"}");
        final Path taskState = context.paths().containerState("sokar-demo-shell");
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
        pin(context, "operator", key);
        final Path repo = published(dir, key, "demo");
        final Reconcile.Result first = new Reconcile(context).run(followed(repo));
        final Path clone = context.paths().followedClone("demo");

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
        pin(one, "operator", key);
        pin(two, "operator", key);

        final Reconcile.Result first = new Reconcile(one).run(followed(repo));
        final Reconcile.Result second = new Reconcile(two).run(followed(repo));

        assertThat(first.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
        assertThat(first.commit()).isEqualTo(second.commit());
        assertThat(Files.readString(one.paths().followedClone("demo").resolve("project.yml")))
                .isEqualTo(Files.readString(
                        two.paths().followedClone("demo").resolve("project.yml")));
    }

    @Test
    void a_key_this_machine_was_never_given_is_its_own_outcome(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        pin(context, "operator", key(dir, "operator"));

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
        pin(context, "operator", key(dir, "operator"));
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
        pin(context, "operator", key(dir, "operator"));

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
        assertThat(context.paths().followedClone("demo").resolve("project.yml")).exists();
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
        pin(context, "operator", key);
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
                        context.paths().followedClone("demo").resolve("project.yml"))
                .repositoryNames()).containsExactly("demo", "backend");
    }

    @Test
    void an_unverified_follow_is_still_unverified_in_the_record_it_writes(@TempDir final Path dir)
            throws IOException {

        // Where it was lost. Every follow ends by writing 'after(known, result)', and that method
        // rebuilt the record through a constructor that defaults the flag - so the follow wrote
        // "unverified" and the next line wrote it away again. The outcome was right, the clone was
        // right, and the one field that says WHO DECIDES read as though a signature had been
        // checked. Found by Agent Frontend, whose dialog shows that field on every project.
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
}
