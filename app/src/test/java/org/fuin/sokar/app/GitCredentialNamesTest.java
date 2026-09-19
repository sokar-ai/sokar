package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which vault entry a git URL reaches for.
 * <p>
 * The scheme decides the kind and the host decides which one. Both halves are measured here
 * because both are the whole interface a person sees: the name they type into
 * {@code sokar vault put} is the name this resolves.
 */
class GitCredentialNamesTest {

    @Test
    void theSchemeDecidesTheKind() {
        assertThat(GitCredentialNames.kindOf("git@github.com:acme/x.git"))
                .isEqualTo(GitCredentialNames.Kind.KEY);
        assertThat(GitCredentialNames.kindOf("ssh://git@gitlab.company.example/acme/x.git"))
                .isEqualTo(GitCredentialNames.Kind.KEY);
        assertThat(GitCredentialNames.kindOf("https://github.com/acme/x.git"))
                .isEqualTo(GitCredentialNames.Kind.TOKEN);
        // A directory on this machine is opened by neither, and saying otherwise would send
        // somebody to store a credential that changes nothing.
        assertThat(GitCredentialNames.kindOf("/home/michi/git/acme"))
                .isEqualTo(GitCredentialNames.Kind.NONE);
    }

    @Test
    void theHostIsReadOffEitherShapeOfUrl() {
        assertThat(GitCredentialNames.hostOf("git@github.com:acme/x.git")).isEqualTo("github.com");
        assertThat(GitCredentialNames.hostOf("https://gitlab.company.example/acme/x.git"))
                .isEqualTo("gitlab.company.example");
        assertThat(GitCredentialNames.hostOf("https://someone@forge.example:8443/x.git"))
                .isEqualTo("forge.example");
        assertThat(GitCredentialNames.hostOf("/home/michi/git/acme")).isNull();
    }

    @Test
    void theHostKeyedEntryIsTriedBeforeThePlainOne() {
        // A company forge and a public one are two credentials. A machine that fell back to one
        // key for both would offer a company's credential to whatever host a project file names,
        // which is the kind of thing nobody notices until it matters.
        assertThat(GitCredentialNames.candidatesFor("git@github.com:acme/x.git"))
                .containsExactly("git.ssh.github.com", "git.ssh", "ssh.default");
        assertThat(GitCredentialNames.candidatesFor("https://github.com/acme/x.git"))
                .containsExactly("git.token.github.com", "git.token");
        assertThat(GitCredentialNames.candidatesFor("/home/michi/git/acme")).isEmpty();
    }

    @Test
    void whatItTellsSomebodyToTypeIsWhatItWouldThenRead() {
        // The commonest way to waste an afternoon is a message naming a name nothing looks in.
        final String url = "https://gitlab.company.example/acme/x.git";
        assertThat(GitCredentialNames.storeCommandFor(url))
                .isEqualTo("sokar vault put git.token.gitlab.company.example");
        assertThat(GitCredentialNames.candidatesFor(url).get(0))
                .isEqualTo("git.token.gitlab.company.example");

        final String ssh = "git@github.com:acme/x.git";
        assertThat(GitCredentialNames.storeCommandFor(ssh))
                .startsWith("sokar vault put " + GitCredentialNames.candidatesFor(ssh).get(0))
                // A key is a file, and the commonest mistake is pasting its public half.
                .contains("< <the private key file>");
    }

    @Test
    void aGitCredentialIsNotAProvidersName() {
        // 'vault put ssh.default' used to warn that no provider was declared under that name,
        // which sent people looking for a mistake they had not made.
        assertThat(GitCredentialNames.isOne("git.token.github.com")).isTrue();
        assertThat(GitCredentialNames.isOne("git.ssh")).isTrue();
        assertThat(GitCredentialNames.isOne("ssh.default")).isTrue();
        assertThat(GitCredentialNames.isOne("anthropic")).isFalse();
    }

    @Test
    void namesACredentialNobodyNamed() {

        // A client that says "vault" and no name means "you name it". Writing "" instead produced
        // a record nothing could store into - 'sokar vault put' with no argument - and a
        // 'sokar vault remove ' with nothing after it. Found by Agent Frontend through the
        // interface's own declare.
        assertThat(GitCredentialNames.impliedName(GitCredentialNames.Kind.KEY,
                "ssh://github.com/acme/x.git")).isEqualTo("git.ssh.github.com");
        assertThat(GitCredentialNames.impliedName(GitCredentialNames.Kind.TOKEN,
                "https://gitlab.example/acme/")).isEqualTo("git.token.gitlab.example");
        // And it is the SAME name the fallback looks in, so a credential declared without a name
        // and one stored without a declaration are one entry rather than two that shadow.
        assertThat(GitCredentialNames.candidatesFor("git@github.com:acme/x.git"))
                .contains(GitCredentialNames.impliedName(GitCredentialNames.Kind.KEY,
                        "ssh://github.com/acme/x.git"));
        // A destination with no host cannot be named after one, and is refused rather than
        // recorded namelessly.
        assertThat(GitCredentialNames.impliedName(GitCredentialNames.Kind.KEY, "/a/path")).isNull();
    }
}
