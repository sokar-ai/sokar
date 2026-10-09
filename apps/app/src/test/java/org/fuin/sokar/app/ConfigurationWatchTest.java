package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link ConfigurationWatch}.
 */
class ConfigurationWatchTest {

    @TempDir
    Path dir;

    @Test
    void aFollowThatWaitedForTheVaultIsFetchedOnceTheVaultOpensAndNoOtherIs() throws Exception {

        // A person opened the vault, and its follow said vault_locked for minutes, until
        // the next round - the walk stood still with the vault open.
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(new ProcessCommandRunner(), new SokarPaths(xdg,
                dir.resolve("bin")), arguments -> 0);
        final FollowedProjects follows = new FollowedProjects(context.paths().projects().followed());
        // An address, so it is fetched: a plain path is a file, which is never fetched.
        final String nowhere = dir.resolve("no-such-repository.git").toUri().toString();
        follows.write(new FollowedProjects.Followed("waited", nowhere, "a1b2c3", "2026-10-07T00:00:00Z",
                "VAULT_LOCKED", "cannot fetch while this account's vault is shut"));
        follows.write(new FollowedProjects.Followed("other", nowhere, "a1b2c3", "2026-10-07T00:00:00Z", "APPLIED",
                ""));
        final boolean[] open = {false};
        final ConfigurationWatch watch = new ConfigurationWatch(context, Duration.ofMinutes(5), () -> open[0]);

        assertThat(watch.afterTheVault()).as("the vault still shut: nothing to do").isEmpty();
        assertThat(outcome(follows, "waited")).isEqualTo("VAULT_LOCKED");

        open[0] = true;
        assertThat(watch.afterTheVault()).as("only what waited for the vault").containsOnlyKeys("waited");
        assertThat(outcome(follows, "waited")).as("fetched, whatever it found").isNotEqualTo("VAULT_LOCKED");
        assertThat(outcome(follows, "other")).isEqualTo("APPLIED");
        assertThat(watch.afterTheVault()).as("nothing waits any more").isEmpty();
    }

    private static String outcome(final FollowedProjects follows, final String name) throws Exception {
        return follows.all().stream().filter(each -> each.name().equals(name)).findFirst().orElseThrow().outcome();
    }
}
