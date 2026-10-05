package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the key a commit was signed with, out of the commit.
 * <p>
 * Against real git and a real signature: the whole point is the exact bytes ssh puts in a signature
 * blob, and a fake would only prove that this agrees with itself.
 */
class SignedByTest {

    private final CommandRunner runner = new ProcessCommandRunner();

    private void git(final Path where, final String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", where.toString()));
        all.addAll(List.of(arguments));
        runner.runOrFail(Command.of(all));
    }

    @Test
    void readsTheKeyAndItsFingerprintOutOfASignedCommit(@TempDir final Path dir)
            throws IOException {

        runner.run(Command.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "alice",
                "-f", dir.resolve("k").toString()));
        final Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "alice@example.com");
        git(repo, "config", "user.name", "Alice");
        git(repo, "config", "gpg.format", "ssh");
        git(repo, "config", "user.signingkey", dir.resolve("k").toString());
        Files.writeString(repo.resolve("a.txt"), "written\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-S", "-m", "signed");

        final String key = SignedBy.keyOf(runner, repo, "HEAD");

        // The same key, byte for byte, as the one on disk - which is what makes it pinnable.
        assertThat(key).isNotNull().startsWith("ssh-ed25519 ");
        final String onDisk = Files.readString(Path.of(dir.resolve("k") + ".pub")).strip();
        assertThat(key).isEqualTo(onDisk.substring(0, onDisk.lastIndexOf(' ')));

        // And the fingerprint ssh-keygen prints for it, which is what a person compares.
        final String expected = runner.runOrFail(Command.of("ssh-keygen", "-lf",
                dir.resolve("k") + ".pub")).standardOutput().strip().split("\\s+")[1];
        assertThat(SignedBy.fingerprintOf(key)).isEqualTo(expected);
    }

    @Test
    void anUnsignedCommitCarriesNoKey(@TempDir final Path dir) throws IOException {
        final Path repo = dir.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main", ".");
        git(repo, "config", "user.email", "a@b.c");
        git(repo, "config", "user.name", "A");
        Files.writeString(repo.resolve("a.txt"), "written\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "--no-gpg-sign", "-m", "plain");

        // Not an error here: the caller is deciding whether to trust it and has a better refusal
        // than this could write.
        assertThat(SignedBy.keyOf(runner, repo, "HEAD")).isNull();
    }

    @Test
    void whatIsAFingerprintAndWhatIsAKey() {
        assertThat(SignedBy.isFingerprint("SHA256:abc")).isTrue();
        assertThat(SignedBy.isFingerprint("ssh-ed25519 AAAA")).isFalse();
        assertThat(SignedBy.isFingerprint(null)).isFalse();
    }
}
