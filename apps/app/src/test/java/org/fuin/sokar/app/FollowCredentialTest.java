package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FollowCredential}: what git is given to reach a destination.
 */
class FollowCredentialTest {

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> "XDG_STATE_HOME".equals(name) ? dir.resolve("state").toString()
                : null, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void anSshDestinationWithNothingLentStillGetsTheMachinesHostKeyPolicy(@TempDir Path dir) {

        // The developer's own agent key reaches the origin, so nothing is lent - and ssh, given no policy, checked the
        // host against the user's own known hosts and refused one this machine had vouched for.
        try (FollowCredential lent = FollowCredential.open(context(dir), "ssh://dev@forge.example/team/repo.git",
                "test")) {
            assertThat(lent.environment().get("GIT_SSH_COMMAND")).contains("StrictHostKeyChecking=yes")
                    .contains("UserKnownHostsFile='" + dir.resolve("state/sokar/known_hosts") + "'");
            // A policy is not a credential: what failed is still answered as nothing stored for it.
            assertThat(lent.lent()).isFalse();
        }
    }

    @Test
    void aDestinationThatIsNotSshIsGivenNoSshPolicy(@TempDir Path dir) {

        try (FollowCredential lent = FollowCredential.open(context(dir), "/srv/git/repo.git", "test")) {
            assertThat(lent.environment()).doesNotContainKey("GIT_SSH_COMMAND");
        }
    }
}
