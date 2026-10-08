package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.credential.Credential;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which key an online task's ssh-agent signs with, and what it is told when there is none.
 */
class OnlineSshKeyTest {

    private static final String UPSTREAM = "git@github.com:sokar-ai/sokar-test-2.git";

    @TempDir
    Path dir;

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void theKeyTheProjectDeclaredForItsUpstreamIsTheOneTheAgentSignsWith() throws IOException {

        // The machine held the project's deploy keys and no 'ssh.default', and the online task's agent
        // was started with that fixed name, failed, and the task could not push.
        final SokarContext context = context();
        new CredentialDeclarations(context).declare(new Credential("deploy:sokar-test-project:sokar-test-2",
                Credential.Kind.SSH_KEY, org.fuin.sokar.core.credential.CredentialRegistry.normalise(UPSTREAM), null,
                Credential.ANY, Credential.Source.VAULT));

        assertThat(FollowCredential.sshKeyFor(context, UPSTREAM)).isEqualTo("deploy:sokar-test-project:sokar-test-2");
        assertThat(FollowCredential.sshKeyFor(context, "https://github.com/sokar-ai/sokar-test-2.git"))
                .as("not an ssh address").isNull();
    }

    @Test
    void withNoKeyTheStartSaysWhichItLookedForAndThatAPushWillBeRefused() {
        final SokarContext context = context();

        assertThat(FollowCredential.sshKeyFor(context, UPSTREAM)).isNull();
        assertThat(CredentialWiring.noSshKey(UPSTREAM)).contains(UPSTREAM)
                .contains(String.join(", ", GitCredentialNames.candidatesFor(UPSTREAM)))
                .contains("a push from inside the task is refused").doesNotContain("did not come up");
    }
}
