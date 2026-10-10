package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.credential.Credential;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Asking what a declaration would do, before anything is written.
 * <p>
 * The last step of a wizard: somebody is about to rely on this, and every refusal the real thing
 * gives has to come out here instead - or the check is worse than none, because it grants
 * confidence it has not earned.
 */
class CredentialDryRunTest {

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void refusesWhatTheRealThingWouldAndWritesNothing(@TempDir final Path dir) {

        final SokarContext context = context(dir);
        final CredentialDeclarations declarations = new CredentialDeclarations(context);

        final CredentialDeclarations.Check would = declarations.wouldDeclare(new Credential("k",
                Credential.Kind.SSH_KEY, "https://github.com/acme/", null, "git",
                Credential.Source.FILE));

        assertThat(would.detail()).contains("reached over https");
        // Nothing written: the file is the record, and a check that recorded what it was asked
        // about would be the very thing it exists to avoid.
        assertThat(context.credentialRegistry().all()).isEmpty();
    }

    @Test
    void saysAValueIsMissingBeforeAnybodyReliesOnIt(@TempDir final Path dir) {

        // With a vault that exists and is open: the value simply is not in it. That is a
        // different answer from having no vault at all, which the test below measures - and
        // telling them apart is the point of both.
        final SokarContext context = context(dir);
        context.vault().write(java.util.Map.of(), "a-passphrase".toCharArray());

        final CredentialDeclarations.Check would = new CredentialDeclarations(context)
                .wouldDeclare(new Credential("", Credential.Kind.TOKEN,
                        "https://forge.example/acme/", null, "git", Credential.Source.VAULT));

        assertThat(would.outcome()).isEqualTo(CredentialDeclarations.Outcome.MISSING_VALUE);
        // Named, though nobody named it - so what a person is told to run is a command that works.
        assertThat(would.storeCommand()).isEqualTo(
                "sokar vault put git.token.forge.example --type token");
    }

    @Test
    void takesTheValueFromTheMachinesOwnFileWhenToldWhereItIs(@TempDir final Path dir)
            throws IOException {

        // What makes "use the key that is already here" one command a client runs unchanged: the
        // name exists only once somebody has said what the key is for, which is here.
        final Path key = dir.resolve("id_ed25519");
        Files.writeString(key, "-----BEGIN OPENSSH PRIVATE KEY-----\n", StandardCharsets.UTF_8);

        final Credential declared = new Credential("", Credential.Kind.SSH_KEY,
                "ssh://github.com", null, "git", Credential.Source.VAULT);

        assertThat(CredentialDeclarations.storeCommandFor(
                CredentialDeclarations.named(declared), key.toString()))
                .isEqualTo("sokar vault put git.ssh.github.com --from-file " + key);
        // And without it, the form that asks for the file to be piped in.
        assertThat(CredentialDeclarations.storeCommandFor(CredentialDeclarations.named(declared)))
                .contains("< <the private key file>");
    }

    @Test
    void readsAUsernameFromAVariableWhenItNamesOne() {

        // One field, one rule. A username is not a secret, but on a CI runner it arrives the same
        // way the token does, and a second field for it would be one more thing to keep in step.
        assertThat(ForgeIdentity.userIn("git@github.com:acme/x.git")).isEqualTo("git");
        assertThat(ForgeIdentity.userIn("ssh://github.com/acme/x.git")).isNull();
        assertThat(ForgeIdentity.userIn("https://someone@forge.example/x.git"))
                .isEqualTo("someone");
    }

    @Test
    void tellsNoVaultApartFromAValueThatWasNeverStored(@TempDir final Path dir) {

        // Two different things to do first. A wizard told MISSING_VALUE sends somebody to
        // 'vault put', which fails at its last step on a machine that has no vault at all.
        // Found on an account that had never made one.
        final CredentialDeclarations.Check would = new CredentialDeclarations(context(dir))
                .wouldDeclare(new Credential("", Credential.Kind.TOKEN,
                        "https://forge.example/", null, "git", Credential.Source.VAULT));

        assertThat(would.outcome()).isEqualTo(CredentialDeclarations.Outcome.NO_VAULT);
        assertThat(would.detail()).contains("no vault yet");
        assertThat(would.storeCommand()).isEqualTo("sokar vault init");
    }
}
