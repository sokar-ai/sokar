package org.fuin.sokar.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImagesTest {

    /** A registry by its host, a repository and a digest: what names the same bytes on every machine. */
    private static final String PINNED = "^[a-z0-9.]+(:[0-9]+)?/.+@sha256:[0-9a-f]{64}$";

    @Test
    void installsInImagesPinnedByDigestAndNamedWithTheirRegistry() {
        // A tag is rebuilt in place, and a short name is resolved by whatever registries a machine's podman is
        // configured to ask - so either would check the packages against something nobody reviewed.
        assertThat(Main.UBUNTU).matches(PINNED).startsWith("docker.io/library/ubuntu:26.04@");
        assertThat(Main.FEDORA).matches(PINNED).startsWith("registry.fedoraproject.org/fedora:");
    }

    @Test
    void installsInTheOldestSystemsSupportedToo() {
        // The packages declare the C library their binaries need; only an install where it is oldest shows the
        // declaration holds, so Debian 13 (glibc 2.41) and Fedora 43 are checked beside the newest.
        assertThat(Main.DEBIAN).matches(PINNED).startsWith("docker.io/library/debian:13@");
        assertThat(Main.FEDORA_OLDEST).matches(PINNED).startsWith("registry.fedoraproject.org/fedora:43@");
    }
}
