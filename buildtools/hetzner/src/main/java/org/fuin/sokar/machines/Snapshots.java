package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Building the images a test leg boots from.
 * <p>
 * <strong>Why this exists at all.</strong> The images it replaces were made by hand and the recipe
 * was written down nowhere - the code that used them said "build one with the snapshot
 * provisioner" and no such thing was in the repository. An image nobody can rebuild is one nobody
 * can change, and it quietly decides things: the first pair were taken on a 320 GB machine to hold
 * 1.6 GB of content, and a snapshot only restores onto a disk at least as big as the one it came
 * from, so every leg had to rent the one server type big enough - at 0.1114 EUR/h against 0.0088
 * for the cheapest that could otherwise have done the work.
 * <p>
 * <strong>What is in one, and why.</strong> Only what a leg cannot install for itself in less time
 * than booting costs: the container runtime and the pieces rootless podman needs, git and the
 * tools to fetch things, an unprivileged {@code build} user that lingers so a user systemd session
 * survives, and the two base images every task starts from. Sokar itself is deliberately absent -
 * building it is what a leg is for.
 */
public final class Snapshots {

    /** Where a snapshot is built: small, because the disk it is taken on becomes its floor. */
    public static final List<String> BUILD_TYPES = List.of("cx23", "cx33", "cpx12");

    /** Which stock image each operating system starts from. */
    private static final Map<String, String> STOCK = Map.of(
            "ubuntu", "ubuntu-26.04",
            "fedora", "fedora-44");

    /** The unprivileged user a leg connects as. */
    private static final String USER = "build";

    /** Base images every task starts from, pulled once here rather than per run. */
    private static final List<String> IMAGES =
            List.of("docker.io/library/ubuntu:24.04", "docker.io/library/alpine:3.20");

    private Snapshots() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Builds one snapshot and leaves nothing running.
     *
     * @param hetzner Where to build it.
     * @param os Which operating system, as the {@code os} label will say.
     * @param types Server types to try, smallest disk first.
     * @param credential The key to connect with.
     * @return The new image's id.
     * @throws IOException If it cannot be built.
     */
    public static long build(Hetzner hetzner, String os, List<String> types,
            Credential credential) throws IOException {
        final String stock = STOCK.get(os);
        if (stock == null) {
            throw new IOException("No stock image for '" + os + "'. Known: " + STOCK.keySet());
        }
        final String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(ZonedDateTime.now(ZoneOffset.UTC));
        final String description = "sokar-ci " + stock + " " + stamp;

        // root, because this is the machine being prepared rather than one being used.
        final Spec spec = new Spec("sokar-snapshot-" + os + "-" + stamp, os, types, "root",
                credential, false);
        try (Lease lease = hetzner.acquireFromStock(spec, stock)) {
            lease.awaitSsh();
            System.out.println("preparing " + os);
            final Ssh.Output prepared = lease.ssh().run(recipe(os));
            if (prepared.status() != 0) {
                throw new IOException("could not prepare " + os + ": " + prepared.all());
            }
            System.out.println(prepared.out().strip());
            // Stopped first: an image of a running machine is a picture of a half-written disk.
            System.out.println("stopping and taking the image");
            hetzner.shutdown(lease.id());
            final long image = hetzner.snapshot(lease.id(), description, os);
            System.out.println("snapshot " + image + ": " + description);
            return image;
        }
    }

    /**
     * Returns what to run on a fresh machine to make it one a leg can use.
     *
     * @param os Which operating system.
     * @return A script.
     */
    static String recipe(String os) {
        final String packages = "fedora".equals(os)
                ? "dnf install -y -q podman nftables git curl gnupg2 ca-certificates "
                        + "shadow-utils slirp4netns passt fuse-overlayfs crun && dnf clean all"
                : "export DEBIAN_FRONTEND=noninteractive && apt-get update -qq && "
                        + "apt-get install -y -qq podman nftables git curl gnupg ca-certificates "
                        + "uidmap slirp4netns passt fuse-overlayfs crun && apt-get clean && "
                        + "rm -rf /var/lib/apt/lists/*";
        return """
            set -eu
            %s
            id -u %s >/dev/null 2>&1 || useradd -m -s /bin/bash %s
            loginctl enable-linger %s
            install -d -m 0700 -o %s -g %s /home/%s/.ssh
            install -m 0600 -o %s -g %s /root/.ssh/authorized_keys /home/%s/.ssh/authorized_keys
            # Pulled as the user that will run them: rootless podman keeps its own store.
            %s
            echo "### prepared"
            df -h / | tail -1
            su - %s -c 'podman images'
            """.formatted(packages, USER, USER, USER, USER, USER, USER, USER, USER, USER,
                    pulls(), USER);
    }

    private static String pulls() {
        final StringBuilder out = new StringBuilder();
        for (final String image : IMAGES) {
            out.append("su - ").append(USER).append(" -c 'podman pull -q ").append(image)
                    .append("'\n");
        }
        return out.toString();
    }
}
