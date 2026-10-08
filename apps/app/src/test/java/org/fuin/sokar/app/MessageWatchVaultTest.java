package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Messages that waited for the vault go once it is open, not at the next pass.
 */
class MessageWatchVaultTest {

    @TempDir
    Path dir;

    @Test
    void aPassThatWaitedForTheVaultIsRunAgainOnceTheVaultOpensAndOnlyOnce() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
        final boolean[] open = {false};
        final MessageWatch watch = new MessageWatch(context, Duration.ofMinutes(1), () -> open[0]);
        final AtomicInteger passes = new AtomicInteger();

        assertThat(watch.afterTheVault(passes::incrementAndGet)).as("nothing waited").isFalse();
        watch.waitedForTheVault();
        assertThat(watch.afterTheVault(passes::incrementAndGet)).as("the vault still shut").isFalse();

        open[0] = true;
        assertThat(watch.afterTheVault(passes::incrementAndGet)).isTrue();
        assertThat(watch.afterTheVault(passes::incrementAndGet)).as("once").isFalse();
        assertThat(passes).hasValue(1);
    }

    @Test
    void theVaultOpeningIsSeenOnceEachTimeAndAtTheFirstLookWhenItIsOpenThen() {
        // After a restart the vault was unlocked and the first message waited for the first timed pass: 64 s.
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final SokarContext context = new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
        final boolean[] open = {true};
        final MessageWatch watch = new MessageWatch(context, Duration.ofMinutes(1), () -> open[0]);

        assertThat(watch.opened()).as("open at the first look").isTrue();
        assertThat(watch.opened()).as("still open").isFalse();
        open[0] = false;
        assertThat(watch.opened()).as("shut").isFalse();
        open[0] = true;
        assertThat(watch.opened()).as("opened again").isTrue();
        assertThat(watch.opened()).isFalse();
    }
}
