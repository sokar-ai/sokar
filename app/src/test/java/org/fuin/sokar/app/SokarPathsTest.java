package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SokarPaths}.
 */
class SokarPathsTest {

    @Test
    void prefersTheOperatorsOwnBuildOverThePackage(@TempDir Path dir) throws IOException {

        // Given
        final Path own = Files.createDirectory(dir.resolve("local"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(own.resolve("sokar-hook-nft"), "");
        Files.writeString(packaged.resolve("sokar-hook-nft"), "");

        // When & Then
        assertThat(SokarPaths.hookBinaries(own, packaged)).isEqualTo(own);
    }

    @Test
    void fallsBackToThePackagedLocation(@TempDir Path dir) throws IOException {

        // A packaged install is the normal case: the hooks are in /usr/libexec/sokar/hooks and
        // nothing is in ~/.local/bin. Looking only at the latter made "sokar setup" fail there.
        final Path own = Files.createDirectory(dir.resolve("local"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(packaged.resolve("sokar-hook-nft"), "");

        assertThat(SokarPaths.hookBinaries(own, packaged)).isEqualTo(packaged);
    }

    @Test
    void namesTheOperatorsDirectoryWhenNothingIsInstalled(@TempDir Path dir) {

        // So the error tells a developer where their build should have put them.
        final Path own = dir.resolve("local");
        assertThat(SokarPaths.hookBinaries(own, dir.resolve("libexec"))).isEqualTo(own);
    }

    @Test
    void anEmptyDirectoryDoesNotCount(@TempDir Path dir) throws IOException {

        final Path own = Files.createDirectory(dir.resolve("local"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(packaged.resolve("sokar-hook-nft"), "");

        // ~/.local/bin exists on almost every machine and is usually not empty; existence of the
        // directory says nothing about whether the hooks are in it.
        Files.writeString(own.resolve("something-else"), "");

        assertThat(SokarPaths.hookBinaries(own, packaged)).isEqualTo(packaged);
    }

    @Test
    void namesThePackagedHooksItHides(@TempDir Path dir) throws IOException {

        // Silent shadowing runs yesterday's firewall code after installing today's package.
        final Path own = Files.createDirectory(dir.resolve("local"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(own.resolve("sokar-hook-nft"), "");
        Files.writeString(packaged.resolve("sokar-hook-nft"), "");

        assertThat(SokarPaths.shadowedHooks(own, packaged)).isEqualTo(packaged);
    }

    @Test
    void reportsNothingShadowedOnAPlainPackagedInstall(@TempDir Path dir) throws IOException {

        // A warning that is always printed is a warning nobody reads.
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(packaged.resolve("sokar-hook-nft"), "");

        assertThat(SokarPaths.shadowedHooks(packaged, packaged)).isNull();
    }

    @Test
    void keysThePassphraseCacheToTheVaultItUnlocks(@TempDir Path dir) {

        // One shared name meant unlocking one vault silently replaced the cached passphrase of
        // another. An acceptance run could log the operator out of their own vault that way.
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final SokarPaths one = new SokarPaths(xdg, dir.resolve("bin"));

        assertThat(one.vaultKeyringKey()).startsWith("sokar:vault:");
        assertThat(one.vaultKeyringKey()).isEqualTo(one.vaultKeyringKey());
    }


    @Test
    void putsAReviewCopyWhereSomebodyWillFindIt(@TempDir Path dir) {

        // Two things this pins, and the second is easy to undo by accident. It must not land in
        // the operator's repository - the default used to be beside the project file, so a review
        // left 'sokar-review-shell/' untracked in a checkout Sokar promises not to touch; one had
        // sat on a test machine for two days. And it must not land under the data directory
        // either: this is the one thing Sokar makes for a person to open in their own editor, and
        // a file dialog does not show .local/share.
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final Path review = new SokarPaths(xdg, dir.resolve("bin"))
                .reviewCheckout("utils4j", "shell");

        assertThat(review).isEqualTo(dir.resolve("sokar").resolve("reviews")
                .resolve("utils4j-shell"));
        assertThat(review.startsWith(xdg.data()))
                .as("visible, not under the data directory").isFalse();
    }

    @Test
    void usesTheOperatorsOwnVaultWhenNothingOverridesIt(@TempDir Path dir) {

        // The negative case: an override that leaked in by accident would send every command at
        // a vault the operator never chose.
        assertThat(new SokarPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")).vaultFile())
                .isEqualTo(XdgPaths.of(name -> null, dir).data().resolve("vault.bin"));
    }
}
