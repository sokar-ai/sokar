package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link ConfigurationGate}, against real git and real ssh keys.
 * <p>
 * Not with a fake runner: what is being checked is whether this machine agrees with git about what
 * a signature means, and a fake would answer that question with itself. The commit object's
 * canonicalisation is git's, and so is the verdict.
 */
class ConfigurationGateTest {

    private final CommandRunner runner = new ProcessCommandRunner();

    private void run(final Path where, final String... command) {
        final java.util.List<String> all = new java.util.ArrayList<>();
        all.add("git");
        all.add("-C");
        all.add(where.toString());
        all.addAll(java.util.List.of(command));
        final var result = runner.run(Command.of(all));
        assertThat(result.exitCode()).as(String.join(" ", all) + " -> " + result.standardError())
                .isZero();
    }

    /** A key pair, and the allowed_signers line that pins it. */
    private Path key(final Path dir, final String name) {
        runner.run(Command.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", name,
                "-f", dir.resolve(name).toString()));
        return dir.resolve(name);
    }

    private Path pin(final Path dir, final String principal, final Path key) throws IOException {
        final String pub = Files.readString(Path.of(key + ".pub")).strip();
        final String[] fields = pub.split("\\s+");
        final Path signers = dir.resolve("configuration_signers");
        Files.writeString(signers, principal + " " + fields[0] + " " + fields[1] + "\n");
        return signers;
    }

    /** A repository with one commit, signed by that key or not signed at all. */
    private Path repository(final Path dir, final Path signingKey) throws IOException {
        final Path repo = dir.resolve("project");
        Files.createDirectories(repo);
        run(repo, "init", "-q", ".");
        run(repo, "config", "user.email", "operator@example.org");
        run(repo, "config", "user.name", "Operator");
        Files.writeString(repo.resolve("project.yml"), "project:\n  name: demo\n");
        run(repo, "add", "project.yml");
        if (signingKey == null) {
            run(repo, "commit", "-q", "--no-gpg-sign", "-m", "configuration");
        } else {
            run(repo, "config", "gpg.format", "ssh");
            run(repo, "config", "user.signingkey", signingKey.toString());
            run(repo, "commit", "-q", "-S", "-m", "configuration");
        }
        return repo;
    }

    @Test
    void applies_what_the_pinned_key_signed(@TempDir final Path dir) throws IOException {
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);

        final ConfigurationGate.Verdict verdict =
                new ConfigurationGate(runner, pin(dir, "operator", key)).verify(repo, "HEAD");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.VERIFIED);
        assertThat(verdict.mayApply()).isTrue();
        assertThat(verdict.signer()).isEqualTo("operator");
        assertThat(verdict.commit()).hasSize(40);
    }

    @Test
    void refuses_a_commit_nobody_signed(@TempDir final Path dir) throws IOException {
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, null);

        final ConfigurationGate.Verdict verdict =
                new ConfigurationGate(runner, pin(dir, "operator", key)).verify(repo, "HEAD");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.NOT_SIGNED);
        assertThat(verdict.detail()).contains("no signature");
        assertThat(verdict.commit()).as("it still says which commit it refused").hasSize(40);
    }

    /**
     * Being signed is not the test; being signed by the right key is. A valid signature by anybody
     * else is the case this exists for.
     */
    @Test
    void refuses_a_good_signature_by_a_key_that_is_not_pinned(@TempDir final Path dir)
            throws IOException {
        final Path pinned = key(dir, "operator");
        final Path somebody = key(dir, "somebody-else");
        final Path repo = repository(dir, somebody);

        final ConfigurationGate.Verdict verdict =
                new ConfigurationGate(runner, pin(dir, "operator", pinned)).verify(repo, "HEAD");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.UNKNOWN_KEY);
        assertThat(verdict.mayApply()).isFalse();
        assertThat(verdict.detail()).contains("not pinned on this machine");
    }

    /** A machine that was never given an anchor verifies nothing, and says so as its own state. */
    @Test
    void a_machine_with_nothing_pinned_applies_nothing(@TempDir final Path dir) throws IOException {
        final Path repo = repository(dir, key(dir, "operator"));

        final ConfigurationGate.Verdict verdict = new ConfigurationGate(runner,
                dir.resolve("never-written")).verify(repo, "HEAD");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.NO_ANCHOR);
        assertThat(verdict.detail()).contains("no key is pinned");
    }

    @Test
    void says_so_when_the_commit_is_not_there(@TempDir final Path dir) throws IOException {
        final Path key = key(dir, "operator");
        final Path repo = repository(dir, key);

        final ConfigurationGate.Verdict verdict = new ConfigurationGate(runner,
                pin(dir, "operator", key)).verify(repo, "no-such-ref");

        assertThat(verdict.outcome()).isEqualTo(ConfigurationGate.Outcome.UNREADABLE);
    }

    @Test
    void aKeyPinnedForOneProjectDoesNotSignForAnother(@TempDir final Path dir) throws IOException {

        // Every project was checked against one file, and any principal in it was accepted: a key pinned for a public
        // project verified a commit served for another one.
        final Path theirs = key(dir, "alpha");
        final Path ours = key(dir, "beta");
        final Path signers = pin(dir, "alpha", theirs);
        final String[] fields = Files.readString(Path.of(ours + ".pub")).strip().split("\\s+");
        Files.writeString(signers, "beta " + fields[0] + " " + fields[1] + "\n", java.nio.file.StandardOpenOption.APPEND);
        final Path repo = repository(dir, theirs);

        assertThat(new ConfigurationGate(runner, signers).verify(repo, "HEAD", "beta").outcome())
                .isEqualTo(ConfigurationGate.Outcome.UNKNOWN_KEY);
        assertThat(new ConfigurationGate(runner, signers).verify(repo, "HEAD", "alpha").outcome())
                .isEqualTo(ConfigurationGate.Outcome.VERIFIED);
    }
}
