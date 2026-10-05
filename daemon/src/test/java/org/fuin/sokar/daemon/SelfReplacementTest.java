package org.fuin.sokar.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SelfReplacement}: a daemon whose binary an update replaced restarts itself, once the update is done.
 */
class SelfReplacementTest {

    @Test
    void aBinaryReplacedOnDiskIsSeenAndARemovedOneIsNot() {

        // An update renames a new binary over the old: the running one is then '(deleted)' with a file at its path.
        // Removed with nothing in its place is an uninstall, which nothing restarts into.
        assertThat(SelfReplacement.replaced("/usr/bin/sokard (deleted)", Set.of(Path.of("/usr/bin/sokard"))::contains))
                .contains(Path.of("/usr/bin/sokard"));
        assertThat(SelfReplacement.replaced("/usr/bin/sokard (deleted)", path -> false)).isEmpty();
        assertThat(SelfReplacement.replaced("/usr/bin/sokard", path -> true)).isEmpty();
    }

    @Test
    void theUnitIsReadFromTheDaemonsOwnControlGroup() {
        assertThat(SelfReplacement.unit("0::/user.slice/user-1000.slice/user@1000.service/app.slice/sokard.service\n"))
                .contains("sokard.service");
        // Started by hand, from a shell: no unit of its own to ask for a restart.
        assertThat(SelfReplacement.unit("0::/user.slice/user-1000.slice/session-3.scope\n")).isEmpty();
    }

    @Test
    void anUpdateSeenTwiceInARowRestartsTheUnitWithItsNewDefinition() {

        // The binary is replaced first and the unit file a moment later, in one update: the first sight waits for
        // the second, and the restart reloads the definitions first, or it ran the new binary under the old unit.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        final SelfReplacement watch = new SelfReplacement(runner, new PrintStream(said),
                () -> "/usr/bin/sokard (deleted)", path -> true, () -> "0::/user.slice/user@1000.service/app.slice/sokard.service");

        assertThat(watch.check()).isFalse();
        assertThat(runner.invocations()).isEmpty();
        assertThat(watch.check()).isTrue();

        assertThat(runner.lines()).containsExactly("systemctl --user daemon-reload",
                "systemctl --user restart --no-block sokard.service");
        assertThat(said.toString()).contains("/usr/bin/sokard was replaced");
    }

    @Test
    void aDaemonStartedByHandIsToldOnceAndNotRestarted() {
        final FakeCommandRunner runner = new FakeCommandRunner();
        final java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        final SelfReplacement watch = new SelfReplacement(runner, new PrintStream(said),
                () -> "/usr/bin/sokard (deleted)", path -> true, () -> "0::/user.slice/session-3.scope");

        watch.check();
        watch.check();
        watch.check();

        assertThat(runner.invocations()).isEmpty();
        assertThat(said.toString().lines().filter(line -> line.contains("restart")).count()).isEqualTo(1);
    }

    @Test
    void anUnchangedBinaryDoesNothing() {
        final FakeCommandRunner runner = new FakeCommandRunner();
        final SelfReplacement watch = new SelfReplacement(runner, new PrintStream(new java.io.ByteArrayOutputStream()),
                () -> "/usr/bin/sokard", path -> true, () -> "0::/app.slice/sokard.service");

        assertThat(watch.check()).isFalse();
        assertThat(watch.check()).isFalse();
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void theRestartEndingTheCallThatAskedForItIsNotAFailure() {

        // Measured on the VM: stopping the unit stops everything in it, the 'systemctl' that asked too, so its answer
        // was a signal - and the log said the restart had failed while the daemon came back on the new binary.
        final FakeCommandRunner runner = new FakeCommandRunner().failing("restart", 143, "");
        final java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        final SelfReplacement watch = new SelfReplacement(runner, new PrintStream(said),
                () -> "/usr/bin/sokard (deleted)", path -> true, () -> "0::/app.slice/sokard.service");

        watch.check();
        assertThat(watch.check()).isTrue();
        assertThat(said.toString()).doesNotContain("failed");
    }
}
