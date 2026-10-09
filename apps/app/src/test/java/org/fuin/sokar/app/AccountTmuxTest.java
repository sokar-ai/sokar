package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.runtime.Containerfile;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for how a person's own tmux settings reach a task: copied in as root, so the agent reads them and cannot change
 * them, and nothing at all without the file.
 */
class AccountTmuxTest {

    private static SokarPaths paths(final Path dir) {
        return new SokarPaths(XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir), dir.resolve("bin"));
    }

    @Test
    void theAccountsFileIsTheOneBesideItsOtherSokarSettings(@TempDir final Path dir) {
        assertThat(paths(dir).tasks().accountTmux()).isEqualTo(dir.resolve("config/sokar/tmux.conf"));
    }

    @Test
    void withoutTheFileNothingIsCopied(@TempDir final Path dir) {
        final TaskRunner runner = new TaskRunner(new FakeCommandRunner(), paths(dir));

        assertThat(runner.accountTmuxSteps("sokar-p-t")).isEmpty();
    }

    @Test
    void theFileIsWrittenAsRootAndLeftReadableOnly(@TempDir final Path dir) throws IOException {
        final SokarPaths paths = paths(dir);
        Files.createDirectories(paths.tasks().accountTmux().getParent());
        Files.writeString(paths.tasks().accountTmux(), "bind -n F5 display-message mine\n");
        final TaskRunner runner = new TaskRunner(new FakeCommandRunner(), paths);

        final List<List<String>> steps = runner.accountTmuxSteps("sokar-p-t");

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0)).containsSubsequence("exec", "--interactive", "--user", "root")
                .contains("of=" + Containerfile.ACCOUNT_TMUX_CONF);
        assertThat(steps.get(1)).containsSubsequence("exec", "--user", "root")
                .containsSubsequence("chmod", "0644", Containerfile.ACCOUNT_TMUX_CONF);
    }
}
