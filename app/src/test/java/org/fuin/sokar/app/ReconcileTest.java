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
        Files.writeString(repo.resolve("project.yml"), """
                project:
                  name: %s
                  description: Followed from a repository
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                """.formatted(project));
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

        assertThat(second.outcome()).isEqualTo(Reconcile.Outcome.REFUSED);
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

        assertThat(result.outcome()).isEqualTo(Reconcile.Outcome.REFUSED);
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
        commit(repo, key, "rewritten-demo");
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
        commit(repo, key, "rewritten-demo");
        git(repo, "branch", "-q", "-M", "rewritten", "main");

        // followed(repo) carries no commit, which is what --accept-rewrite leaves behind.
        final Reconcile.Result accepted = reconcile.run(followed(repo));

        assertThat(accepted.outcome()).isEqualTo(Reconcile.Outcome.APPLIED);
    }
}
