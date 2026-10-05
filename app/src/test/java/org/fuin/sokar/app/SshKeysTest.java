package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the machine can say about the keys it already has.
 * <p>
 * Measured against keys written by {@code ssh-keygen}, because what is being read is somebody
 * else's format and a fixture would only prove this agrees with itself.
 */
class SshKeysTest {

    private Path keygen(final Path home, final String name, final String... extra)
            throws IOException, InterruptedException {
        final Path directory = home.resolve(".ssh");
        Files.createDirectories(directory);
        final Path file = directory.resolve(name);
        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                "ssh-keygen", "-q", "-f", file.toString(), "-C", name + "@company"));
        command.addAll(java.util.List.of(extra));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor()).as("ssh-keygen").isZero();
        return file;
    }

    @Test
    void describesAKeyWithoutReadingItsValue(@TempDir final Path home) throws Exception {

        keygen(home, "id_ed25519", "-t", "ed25519", "-N", "");

        final SshKeys.Key key = new SshKeys(home).all().get(0);

        assertThat(key.type()).isEqualTo("ssh-ed25519");
        assertThat(key.fingerprint()).startsWith("SHA256:");
        assertThat(key.comment()).isEqualTo("id_ed25519@company");
        assertThat(key.encrypted()).isFalse();
        assertThat(key.privateHalf()).isTrue();
        assertThat(key.usable()).isTrue();
        assertThat(key.obstacle()).isNull();
        // The fingerprint is the one ssh-keygen itself prints, or it is worth nothing as a way to
        // tell two keys apart.
        final Process shown = new ProcessBuilder("ssh-keygen", "-lf",
                home.resolve(".ssh/id_ed25519.pub").toString()).start();
        assertThat(new String(shown.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .contains(key.fingerprint());
    }

    @Test
    void saysWhyAKeyWithAPassphraseWillNotDo(@TempDir final Path home) throws Exception {

        keygen(home, "id_locked", "-t", "ed25519", "-N", "a-passphrase");

        final SshKeys.Key key = new SshKeys(home).all().get(0);

        assertThat(key.encrypted()).isTrue();
        assertThat(key.usable()).isFalse();
        // The type lives inside the encrypted part; saying "" is the truth, and the public half
        // still gives the fingerprint and the comment.
        assertThat(key.fingerprint()).startsWith("SHA256:");
        assertThat(key.obstacle()).contains("ssh-keygen -p");
    }

    @Test
    void listsAKeyThisMachineCannotSignWithRatherThanHidingIt(@TempDir final Path home)
            throws Exception {

        // An RSA key is perfectly usable by ssh itself, where it lies. A list that threw on it -
        // or left it out - would be a list nobody could trust.
        keygen(home, "id_rsa", "-t", "rsa", "-b", "2048", "-N", "");

        final SshKeys.Key key = new SshKeys(home).all().get(0);

        assertThat(key.type()).isEqualTo("ssh-rsa");
        assertThat(key.usable()).isFalse();
        assertThat(key.obstacle()).contains("used where it lies");
    }

    @Test
    void namesAPublicHalfWithNoPrivateOne(@TempDir final Path home) throws Exception {

        // The mistake anybody makes once: pointing a credential at the .pub. It is listed, so the
        // list can say why it will not do, instead of leaving somebody to find out at a fetch.
        keygen(home, "id_ed25519", "-t", "ed25519", "-N", "");
        Files.delete(home.resolve(".ssh/id_ed25519"));

        final SshKeys.Key key = new SshKeys(home).all().get(0);

        assertThat(key.privateHalf()).isFalse();
        assertThat(key.obstacle()).contains("only the public half");
    }

    @Test
    void findsAKeyNamedInTheConfigAndSaysSo(@TempDir final Path home) throws Exception {

        // A company setup usually names its key in ~/.ssh/config, often outside ~/.ssh entirely.
        // A list that ignored it would look wrong to exactly the people who need this most.
        final Path elsewhere = home.resolve("work");
        Files.createDirectories(elsewhere);
        final Process made = new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "",
                "-C", "work@company", "-f", elsewhere.resolve("company_key").toString()).start();
        assertThat(made.waitFor()).isZero();
        Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(home.resolve(".ssh/config"),
                "Host forge\n  IdentityFile ~/work/company_key\n", StandardCharsets.UTF_8);

        final java.util.List<SshKeys.Key> keys = new SshKeys(home).all();

        assertThat(keys).singleElement().satisfies(key -> {
            assertThat(key.path()).isEqualTo(elsewhere.resolve("company_key").toString());
            assertThat(key.found()).isEqualTo(SshKeys.Found.CONFIGURED);
            assertThat(key.comment()).isEqualTo("work@company");
        });
    }

    @Test
    void ignoresWhatIsNotAKeyAtAll(@TempDir final Path home) throws Exception {

        Files.createDirectories(home.resolve(".ssh"));
        Files.writeString(home.resolve(".ssh/known_hosts"), "github.com ssh-ed25519 AAAA\n");
        Files.writeString(home.resolve(".ssh/config"), "Host x\n  User y\n");

        assertThat(new SshKeys(home).all()).isEmpty();
    }
}
