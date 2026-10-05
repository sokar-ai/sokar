package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A locked vault stopped every conversation without a word (Agent Matrix, 2026-10-04).
 */
class VaultLockedConversationsTest {

    @TempDir
    private Path dir;

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
    void aVaultThatIsThereAndShutIsSaidAsWhatTheConversationsWaitFor() throws IOException {
        final SokarContext context = context();
        assertThat(Conversations.vaultWait(context)).as("no vault at all: nothing to wait for").isEmpty();

        Files.createDirectories(context.paths().vault().vaultFile().getParent());
        Files.writeString(context.paths().vault().vaultFile(), "sealed");

        assertThat(Conversations.vaultWait(context)).contains("the vault is locked").contains("sokar vault unlock");
    }
}
