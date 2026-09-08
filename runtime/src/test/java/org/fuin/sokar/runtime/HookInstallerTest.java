package org.fuin.sokar.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link HookInstaller}.
 */
class HookInstallerTest {

    private HookInstaller installer(Path root) {
        return new HookInstaller(root.resolve("containers/oci/hooks.d"), root.resolve("bin"));
    }

    @Test
    void writesOneDescriptorPerHookAndStage(@TempDir Path root) {

        // The runtime does not tell a hook which stage fired, so the stage has to be in the
        // arguments - which means one file per stage, not one file per hook.
        assertThat(installer(root).descriptors()).containsOnlyKeys(
                "sokar-hook-nft-createRuntime.json",
                "sokar-hook-nft-poststop.json",
                "sokar-hook-supervisor-createRuntime.json",
                "sokar-hook-supervisor-poststop.json",
                "sokar-hook-reader-createRuntime.json",
                // The reader starts a long-running process, so it needs a stage that stops it.
                "sokar-hook-reader-poststop.json");
    }

    @Test
    void tellsEachHookWhichStageItIsRunningAt(@TempDir Path root) {

        final Map<String, String> descriptors = installer(root).descriptors();

        assertThat(hook(descriptors.get("sokar-hook-nft-poststop.json")).get("args").toString())
                .contains("poststop");
        assertThat(descriptor(descriptors.get("sokar-hook-nft-poststop.json")).get("stages").toString())
                .contains("poststop");
    }

    @Test
    void gatesEveryHookOnSokarsOwnAnnotation(@TempDir Path root) {

        // Without the gate these fire for every container the operator runs, and the fail-closed
        // nft hook would then refuse to start unrelated containers.
        for (final String json : installer(root).descriptors().values()) {
            final Map<?, ?> when = (Map<?, ?>) descriptor(json).get("when");
            final Map<?, ?> annotations = (Map<?, ?>) when.get("annotations");
            assertThat(annotations).hasSize(1);
            assertThat(annotations.keySet().iterator().next().toString())
                    .isEqualTo("org\\.fuin\\.sokar\\.sidecar");
        }
    }

    @Test
    void escapesTheAnnotationSoItIsNotTreatedAsAWildcard(@TempDir Path root) {

        // podman matches the key as a regular expression. Unescaped dots would let an annotation
        // like 'orgXfuinYsokarZsidecar' switch the hooks on.
        final String json = installer(root).descriptors().values().iterator().next();

        assertThat(json).contains("org\\\\.fuin\\\\.sokar\\\\.sidecar");
        assertThat(Sidecar.ANNOTATION).isEqualTo("org.fuin.sokar.sidecar");
    }

    @Test
    void pointsAtTheInstalledBinaries(@TempDir Path root) {

        final String json = installer(root).descriptors().get("sokar-hook-nft-createRuntime.json");

        assertThat(hook(json).get("path"))
                .isEqualTo(root.resolve("bin/sokar-hook-nft").toString());
    }

    @Test
    void writesTheDescriptorsAndTheDropIn(@TempDir Path root) throws IOException {

        final var written = installer(root).install();

        assertThat(written).hasSize(7);
        assertThat(root.resolve("containers/containers.conf.d/50-sokar.conf")).exists();
        assertThat(root.resolve("containers/oci/hooks.d/sokar-hook-nft-createRuntime.json")).exists();
    }

    @Test
    void theDropInPointsPodmanAtTheHookDirectory(@TempDir Path root) throws IOException {

        installer(root).install();

        // podman's own default hooks_dir is a root-owned system path, so a rootless install has
        // to add one or the hooks never fire at all.
        assertThat(Files.readString(root.resolve("containers/containers.conf.d/50-sokar.conf")))
                .contains("[engine]")
                .contains("hooks_dir = [\"" + root.resolve("containers/oci/hooks.d") + "\"]");
    }

    @Test
    void installingTwiceIsHarmless(@TempDir Path root) throws IOException {

        installer(root).install();
        final var second = installer(root).install();

        assertThat(second).hasSize(7);
    }

    @Test
    void uninstallRemovesEverythingItWrote(@TempDir Path root) throws IOException {

        final List<Path> written = installer(root).install();

        final List<Path> removed = installer(root).uninstall();

        // Named rather than counted, and both directories among them: the drop-in does not live
        // beside the hooks, so a count with one directory beside it reported the one file
        // somebody is most likely to go looking for by hand in the wrong place.
        assertThat(removed).containsExactlyInAnyOrderElementsOf(written);
        assertThat(removed).anySatisfy(file ->
                assertThat(file.getParent().getFileName()).hasToString("hooks.d"));
        assertThat(removed).anySatisfy(file ->
                assertThat(file.getParent().getFileName()).hasToString("containers.conf.d"));
        assertThat(installer(root).uninstall()).isEmpty();
    }

    @Test
    void reportsMissingBinaries(@TempDir Path root) throws IOException {

        assertThat(installer(root).binariesPresent()).isFalse();

        Files.createDirectories(root.resolve("bin"));
        for (final String name : new String[] { "sokar-hook-nft", "sokar-hook-supervisor", "sokar-hook-reader" }) {
            final Path binary = root.resolve("bin").resolve(name);
            Files.writeString(binary, "#!/bin/sh\n");
            binary.toFile().setExecutable(true);
        }

        assertThat(installer(root).binariesPresent()).isTrue();
    }

    private Map<?, ?> descriptor(String json) {
        return (Map<?, ?>) Json.parse(json);
    }

    private Map<?, ?> hook(String json) {
        return (Map<?, ?>) descriptor(json).get("hook");
    }

    @Test
    void reportsHooksThatWereNeverRegistered(@TempDir Path root) throws IOException {

        // The state that matters most: the package is installed, doctor was green, and every
        // container would have started with no firewall at all.
        final HookInstaller installer = installer(root);
        binaries(root);

        assertThat(installer.registration()).isEqualTo(HookInstaller.Registration.MISSING);
    }

    @Test
    void reportsRegisteredHooks(@TempDir Path root) throws IOException {

        final HookInstaller installer = installer(root);
        binaries(root);
        installer.install();

        assertThat(installer.registration()).isEqualTo(HookInstaller.Registration.ACTIVE);
    }

    @Test
    void reportsDescriptorsLeftBehindByAnUpgrade(@TempDir Path root) throws IOException {

        // The case nobody was catching: a package replaces the binaries and never touches these
        // files, and nothing runs 'sokar setup' for the operator. Every check asked only whether
        // the files were THERE, so a descriptor from an older release reported ACTIVE while
        // podman went on running what it said - and a container with no firewall looks entirely
        // normal.
        final HookInstaller installer = installer(root);
        binaries(root);
        installer.install();

        final Path descriptor = root.resolve("containers/oci/hooks.d/sokar-hook-nft-poststop.json");
        Files.writeString(descriptor, Files.readString(descriptor)
                .replace("\"timeout\":30", "\"timeout\":10"));

        assertThat(installer.registration()).isEqualTo(HookInstaller.Registration.STALE);
        assertThat(installer.outdated()).containsExactly(descriptor);
    }

    @Test
    void reportsADropInLeftBehindByAnUpgrade(@TempDir Path root) throws IOException {

        // The drop-in is the file that tells podman to read the descriptors at all, and it lives
        // somewhere else, so it is easy to check the wrong half.
        final HookInstaller installer = installer(root);
        binaries(root);
        installer.install();
        Files.writeString(installer.dropInFile(),
                installer.podmanDropIn() + "# edited by somebody\n");

        assertThat(installer.registration()).isEqualTo(HookInstaller.Registration.STALE);
        assertThat(installer.outdated()).containsExactly(installer.dropInFile());
    }

    @Test
    void namesEveryFileThatWouldBeRewritten(@TempDir Path root) throws IOException {

        // 'run setup again' after an upgrade that changed nothing visible reads like
        // superstition until the message can say which files differ.
        final HookInstaller installer = installer(root);
        binaries(root);
        installer.install();
        for (final String name : installer.descriptors().keySet()) {
            Files.writeString(root.resolve("containers/oci/hooks.d").resolve(name), "{}");
        }

        assertThat(installer.outdated())
                .hasSize(installer.descriptors().size())
                .allSatisfy(file -> assertThat(file.getFileName().toString()).endsWith(".json"));
    }

    @Test
    void saysNothingIsOutdatedWhenEverythingMatches(@TempDir Path root) throws IOException {

        // The negative case. A check that answered "stale" for a correct installation would send
        // every operator to run setup after every command.
        final HookInstaller installer = installer(root);
        binaries(root);
        installer.install();

        assertThat(installer.outdated()).isEmpty();
        assertThat(installer.registration()).isEqualTo(HookInstaller.Registration.ACTIVE);
    }

    @Test
    void reportsDescriptorsNamingBinariesThatAreGone(@TempDir Path root) throws IOException {

        // What a package upgrade leaves behind, and it looks identical to a working install.
        final HookInstaller installer = installer(root);
        binaries(root);
        installer.install();
        try (var files = java.nio.file.Files.list(root.resolve("bin"))) {
            for (final Path binary : files.toList()) {
                java.nio.file.Files.delete(binary);
            }
        }

        assertThat(installer.registration()).isEqualTo(HookInstaller.Registration.DANGLING);
    }

    @Test
    void reportsHooksAnotherDropInHasSwitchedOff(@TempDir Path root) throws IOException {

        // Drop-ins are read in name order and each hooks_dir replaces the last, so a file sorting
        // after Sokar's own switches the hooks off while every descriptor stays present and right.
        final HookInstaller installer = installer(root);
        binaries(root);
        installer.install();
        java.nio.file.Files.writeString(
                installer.dropInFile().getParent().resolve("99-other.conf"),
                "[engine]\nhooks_dir = [\"/somewhere/else\"]\n");

        assertThat(installer.registration()).isEqualTo(HookInstaller.Registration.SHADOWED);
        assertThat(installer.effectiveHooksDirectories()).containsExactly("/somewhere/else");
    }

    private void binaries(Path root) throws IOException {
        java.nio.file.Files.createDirectories(root.resolve("bin"));
        for (final String name : java.util.List.of("sokar-hook-nft", "sokar-hook-supervisor",
                "sokar-hook-reader")) {
            final Path binary = root.resolve("bin").resolve(name);
            java.nio.file.Files.writeString(binary, "#!/bin/sh\n");
            binary.toFile().setExecutable(true);
        }
    }
}
