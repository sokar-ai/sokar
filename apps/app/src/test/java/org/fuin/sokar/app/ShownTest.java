package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for what a refusal shows at once instead of naming the command that would show it.
 */
class ShownTest {

    @TempDir
    private Path dir;

    private SokarContext context() {
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(XdgPaths.of(name -> null, dir),
                dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void theTasksThereAreAreNamedInTheRefusal() {
        assertThat(Shown.names("the tasks here", List.of("sokar-p-a", "sokar-p-b"), "there is no task here"))
                .isEqualTo("the tasks here: sokar-p-a, sokar-p-b");
        assertThat(Shown.names("the tasks here", List.of(), "there is no task here")).isEqualTo("there is no task here");
    }

    @Test
    void whyAFollowedProjectIsNotInForceIsSaidFromItsRecord() throws Exception {
        final SokarContext context = context();
        new FollowedProjects(context.paths().projects().followed()).write(new FollowedProjects.Followed("shop",
                "git@forge.example.org:o/shop.git", "", "2026-10-10T11:00:00Z", "UNKNOWN_KEY",
                "signed by a key this machine was never given", "abc123", "", false));

        assertThat(Shown.whyNotInForce(context, "shop")).contains("unknown_key")
                .contains("signed by a key this machine was never given");
        assertThat(Shown.followed(context)).isEqualTo("the projects followed here: shop");
    }

    @Test
    void aChangedPassphraseIsKeptWhereTheOldOneWasRatherThanDroppedForAnUnlock() {
        final java.util.List<String> kept = new java.util.ArrayList<>();

        assertThat(VaultPassphraseCommand.afterRekey(org.fuin.sokar.vault.KernelKeyring.Forgotten.CLEARED,
                passphrase -> kept.add(new String(passphrase)), "new".toCharArray()))
                .contains("the new passphrase");
        assertThat(kept).containsExactly("new");
        assertThat(VaultPassphraseCommand.afterRekey(org.fuin.sokar.vault.KernelKeyring.Forgotten.CLEARED,
                passphrase -> false, "new".toCharArray())).contains("'sokar vault unlock' with the new one");
    }
}
