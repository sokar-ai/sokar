package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        // Silent shadowing is how an operator ends up running yesterday's firewall code after
        // installing today's package. doctor prints this.
        final Path own = Files.createDirectory(dir.resolve("local"));
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(own.resolve("sokar-hook-nft"), "");
        Files.writeString(packaged.resolve("sokar-hook-nft"), "");

        assertThat(SokarPaths.shadowedHooks(own, packaged)).isEqualTo(packaged);
    }

    @Test
    void reportsNothingShadowedOnAPlainPackagedInstall(@TempDir Path dir) throws IOException {

        // The negative case: with no local build there is nothing to warn about, and a warning
        // that is always printed is a warning nobody reads.
        final Path packaged = Files.createDirectory(dir.resolve("libexec"));
        Files.writeString(packaged.resolve("sokar-hook-nft"), "");

        assertThat(SokarPaths.shadowedHooks(packaged, packaged)).isNull();
    }
}
