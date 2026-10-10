package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for reading a credential in.
 * <p>
 * The interesting property is not what the value comes back as - it is <em>which stream was
 * touched</em>. Reading a typed secret through the echoing path put it on the screen and into the
 * terminal's scrollback, and no assertion on the returned value could tell the two apart. So each
 * test makes the wrong source throw.
 */
class VaultPutCommandTest {

    /** Fails the test if anything reads it. */
    private static BufferedReader forbidden() {
        return new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                throw new IOException("standard input was read for a secret somebody typed");
            }
        };
    }

    @Test
    void leavesStandardInputOpenForThePassphrasePromptThatFollows() throws IOException {

        // Reported on the VM: reading the value closed standard input, the vault file then took
        // descriptor 0, and the passphrase prompt switched echo off on a regular file - failing
        // with "Inappropriate ioctl for device" whenever the vault was locked.
        final boolean[] closed = { false };
        final java.io.InputStream in = new java.io.ByteArrayInputStream(
                "sk-piped\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };

        assertThat(VaultPutCommand.valueFrom("anthropic", null, in, null)).isEqualTo("sk-piped");
        assertThat(closed[0]).as("standard input was closed").isFalse();
    }

    @Test
    void saysSoWhenTheNameIsAnAgentsRatherThanAProviders() {

        // Stored under 'claude', a key is still found by a task running claude - but only as a
        // fallback, and the old warning said nothing would find it, which was not true either.
        final String said = VaultPutCommand.unknownName("claude",
                java.util.Set.of("openrouter", "anthropic"), java.util.Set.of("claude"));

        assertThat(said).contains("'claude' is an agent's name")
                .contains("declared: anthropic, openrouter")
                .contains("sokar providers")
                .contains("sokar vault remove claude");
    }

    @Test
    void namesTheDeclaredProvidersWhenTheNameIsNobodys() {

        final String said = VaultPutCommand.unknownName("antropic", java.util.Set.of("anthropic"),
                java.util.Set.of("claude"));

        assertThat(said).contains("no provider 'antropic' is declared")
                .contains("declared: anthropic")
                .doesNotContain("agent's name");
    }

    @Test
    void aTypedSecretIsNeverReadFromTheEchoingStream() throws IOException {

        // The bug this method exists for. A terminal echoes what is typed on standard input, so
        // reading a person's credential there prints it and leaves it in the scrollback.
        final String value = VaultPutCommand.readValue(true, () -> "sk-typed".toCharArray(),
                forbidden());

        assertThat(value).isEqualTo("sk-typed");
    }

    @Test
    void aPipedSecretIsNeverAskedForAtATerminal() throws IOException {

        // 'printf %s ... | sokar vault put' is how scripts and the acceptance suite do this. There
        // is no terminal to prompt at, and prompting anyway would hang waiting for a person who is
        // not there - which reads as a stuck build.
        final String value = VaultPutCommand.readValue(false, () -> {
            throw new IllegalStateException("a terminal was asked for a piped secret");
        }, new BufferedReader(new StringReader("sk-piped\n")));

        assertThat(value).isEqualTo("sk-piped");
    }

    @Test
    void whatWasTypedIsWipedOnceItHasBeenRead() throws IOException {

        // Only shortens one copy's life - the stored value is a String either way. It is the copy
        // that exists for no reason after this point, and leaving it is how a secret ends up in a
        // heap dump nobody expected to contain one.
        final char[] secret = "sk-typed".toCharArray();

        VaultPutCommand.readValue(true, () -> secret, forbidden());

        assertThat(secret).containsOnly('\0');
    }

    @Test
    void surroundingWhitespaceIsNotPartOfTheCredential() throws IOException {

        // A paste carries a trailing newline more often than not, and a credential with one on the
        // end fails as an authentication error that looks exactly like a wrong key.
        assertThat(VaultPutCommand.readValue(true, () -> "  sk-typed \n".toCharArray(),
                forbidden())).isEqualTo("sk-typed");
        assertThat(VaultPutCommand.readValue(false, () -> null,
                new BufferedReader(new StringReader("  sk-piped  \n")))).isEqualTo("sk-piped");
    }

    @Test
    void typingNothingIsEmptyRatherThanAFailure() throws IOException {

        // Console.readPassword answers null at end of input - somebody pressing ctrl-D. The caller
        // refuses an empty value with a sentence; a NullPointerException here would report that as
        // a fault in Sokar.
        assertThat(VaultPutCommand.readValue(true, () -> null, forbidden())).isEmpty();
        assertThat(VaultPutCommand.readValue(false, () -> null,
                new BufferedReader(new StringReader("")))).isEmpty();
    }

    @Test
    void aReaderThatFailsIsNotSwallowed() {

        assertThatThrownBy(() -> VaultPutCommand.readValue(false, () -> null, forbidden()))
                .isInstanceOf(IOException.class);
    }

    @org.junit.jupiter.api.Test
    void asksForAGrantsClientSecretByNameAndTakesNoneForAPublicClient() {
        // "Value for 'github-copilot':" - nobody could tell that it meant the client secret, nor that
        // Copilot's public client has none (2026-10-09).
        assertThat(VaultPutCommand.prompt("github-copilot", "oauth-device"))
                .isEqualTo("Client secret for 'github-copilot' (none for a public client: press Enter): ");
        assertThat(VaultPutCommand.prompt("anthropic", "api-key")).isEqualTo("Value for 'anthropic': ");
        assertThat(VaultPutCommand.grantValue("", "oauth-device")).as("a public client").isEqualTo("-");
        assertThat(VaultPutCommand.grantValue("", "oauth-code")).isEqualTo("-");
        assertThat(VaultPutCommand.grantValue("s3cret", "oauth-device")).isEqualTo("s3cret");
        assertThat(VaultPutCommand.grantValue("", "api-key")).as("a key is never empty").isEmpty();
    }
}
