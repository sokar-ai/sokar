package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the recipe {@link Snapshots} runs.
 */
class SnapshotsTest {

    @Test
    void installsTheJdkWhereTheRemoteBuildLooksForIt() {
        // The first rebuild of these snapshots left it out and the build died on "The JAVA_HOME
        // environment variable is not defined correctly" - an inventory taken from dpkg cannot
        // see something unpacked into /opt.
        assertThat(Snapshots.recipe("ubuntu")).contains("/opt/graalvm");
        assertThat(Snapshots.recipe("ubuntu")).contains("graalvm-community-jdk-25.0.2");
        assertThat(Snapshots.recipe("ubuntu")).contains("/opt/graalvm/bin/java --version");
    }

    @Test
    void bringsTheToolchainNativeImageLinksWith() {
        assertThat(Snapshots.recipe("ubuntu")).contains("build-essential").contains("zlib1g-dev");
        assertThat(Snapshots.recipe("fedora")).contains("gcc").contains("zlib-devel");
    }

    @Test
    void usesEachDistributionsOwnPackageManager() {
        assertThat(Snapshots.recipe("ubuntu")).contains("apt-get").doesNotContain("dnf ");
        assertThat(Snapshots.recipe("fedora")).contains("dnf ").doesNotContain("apt-get");
    }

    @Test
    void stopsThePackageManagerUpdatingItselfDuringATimedBuild() {
        // The same hello-world native image took 7m38s on one boot and 1m08s on the next, from
        // the same snapshot on the same server type. A leg cannot be timed on a machine that
        // decides to fetch updates while it works.
        assertThat(Snapshots.recipe("ubuntu")).contains("unattended-upgrades")
                .contains("apt-daily.timer");
        assertThat(Snapshots.recipe("fedora")).contains("makecache");
    }

    @Test
    void checksTheJdkAgainstItsPublishedDigest() {
        // Everything else installed here is verified - the agent CLI by SHA-256, musl by digest.
        // A JDK trusted because the host answered was the one exception nobody decided to make.
        assertThat(Snapshots.recipe("ubuntu"))
                .contains("e0be791c8fda4d03b6b0a0cb824fef3149736170057b3a515252b44419606af0")
                .contains("sha256sum -c -");
    }

    @Test
    void bringsTheResolverSokarDrivesTheFirewallWith() {
        // Sokar's package pulls podman, nftables and dnsmasq as dependencies, but a leg builds
        // Sokar from source and installs no package - so nothing pulled dnsmasq and six egress
        // checks failed on a machine whose firewall could open nothing.
        assertThat(Snapshots.recipe("ubuntu")).contains("dnsmasq-base");
        assertThat(Snapshots.recipe("fedora")).contains("dnsmasq");
    }

    @Test
    void refusesAResolverThatCannotOpenAnything() {
        // Present is not enough: a dnsmasq without nftset support resolves everything and opens
        // nothing, which fails later as a task that cannot reach a host the project declared.
        assertThat(Snapshots.recipe("ubuntu")).contains("grep -q nftset");
    }

    @Test
    void leavesNoPlaceholderUnreplaced() {
        // A stray @NAME@ would reach the machine as a literal and fail somewhere unhelpful.
        assertThat(Snapshots.recipe("ubuntu")).doesNotContain("@");
        assertThat(Snapshots.recipe("fedora")).doesNotContain("@");
    }

    @Test
    void makesTheUserThatLingersAndCanBeReached() {
        assertThat(Snapshots.recipe("ubuntu")).contains("useradd -m -s /bin/bash build");
        assertThat(Snapshots.recipe("ubuntu")).contains("loginctl enable-linger build");
        assertThat(Snapshots.recipe("ubuntu")).contains("authorized_keys");
    }

    @Test
    void pullsTheBaseImagesAsTheUserThatWillRunThem() {
        // Rootless podman keeps its own store per user; pulled as root they would be invisible.
        assertThat(Snapshots.recipe("ubuntu"))
                .contains("su - build -c 'podman pull -q docker.io/library/ubuntu:24.04'");
    }
}
