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
 * from, so every leg had to rent the one server type big enough - at 0.1114 EUR/h against 0.0136
 * for one that does the work in about the same money.
 * <p>
 * <strong>What is in one, and why.</strong> Only what a leg cannot install for itself in less time
 * than booting costs: the JDK it builds with, the C toolchain that JDK links against, the
 * container runtime and the pieces rootless podman needs, an unprivileged {@code build} user that
 * lingers so a user systemd session survives, and the two base images every task starts from.
 * Sokar itself is deliberately absent - building it is what a leg is for.
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

    /**
     * The JDK a leg builds with.
     * <p>
     * <strong>Not on the PATH, deliberately.</strong> The remote build names it -
     * {@code JAVA_HOME=/opt/graalvm} - so a machine where somebody installed a different java does
     * not quietly build with that one instead.
     * <p>
     * Left out of the first rebuild of these snapshots and found the hard way: the build died on
     * "The JAVA_HOME environment variable is not defined correctly", because an inventory that
     * asked dpkg what was installed never thought to look in {@code /opt}.
     */
    private static final String GRAALVM_VERSION = "25.0.2";

    /**
     * The published digest of that archive.
     * <p>
     * Everything else this project installs is verified - the agent CLI by SHA-256, npm by
     * lockfile integrity, the musl toolchain by digest - and a JDK fetched over TLS and trusted
     * because the host answered is the one exception nobody decided to make.
     */
    private static final String GRAALVM_SHA256 =
            "e0be791c8fda4d03b6b0a0cb824fef3149736170057b3a515252b44419606af0";

    /** Where the JDK goes, because that is where the remote build looks for it. */
    private static final String GRAALVM_HOME = "/opt/graalvm";

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
        return build(hetzner, os, types, credential, null, null);
    }

    /**
     * Builds one snapshot, proving it by building the product on it.
     * <p>
     * <strong>The image is verified by the thing it exists for.</strong> Two rebuilds of these
     * snapshots shipped broken because they were checked by booting them and running a
     * hello-world native image - which links nothing statically and so never touched the musl
     * toolchain the hooks need, and needs no JDK on the machine at all. A snapshot that has
     * compiled Sokar once cannot be missing what compiling Sokar needs.
     * <p>
     * It also leaves {@code ~/.m2} warm, which is the difference between a leg that downloads its
     * dependencies and one that does not - and a leg's wall-clock is charged to CI minutes.
     *
     * @param hetzner Where to build it.
     * @param os Which operating system.
     * @param types Server types to try, smallest disk first.
     * @param credential The key to connect with.
     * @param archive A tar of the working tree to build once, or {@code null} to skip that.
     * @param musl The {@code install-musl.sh} to run, or {@code null} to skip it.
     * @return The new image's id.
     * @throws IOException If it cannot be built.
     */
    public static long build(Hetzner hetzner, String os, List<String> types,
            Credential credential, java.nio.file.Path archive, String musl) throws IOException {
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
            if (musl != null) {
                System.out.println("installing the musl toolchain");
                run(lease, "cat > /tmp/install-musl.sh && chmod +x /tmp/install-musl.sh", musl);
                run(lease, "su - " + USER + " -c 'bash /tmp/install-musl.sh'", null);
            }
            if ("fedora".equals(os)) {
                // Hetzner's Fedora image ships SELinux permissive. Everything above was installed
                // in that state, so the relabel is not optional - and a relabel needs a reboot.
                System.out.println("turning SELinux on, and relabelling");
                run(lease, "sed -i 's/^SELINUX=.*/SELINUX=enforcing/' /etc/selinux/config "
                        + "&& touch /.autorelabel", null);
                lease.ssh().run("systemctl reboot");
                lease.ssh().disconnect();
                lease.awaitSsh();
                lease.ssh().reconnect();
                run(lease, "getenforce | grep -qx Enforcing", null);
            }
            if (archive != null) {
                System.out.println("building the product once, to prove this image can");
                lease.ssh().upload(archive, "/tmp/tree.tar");
                run(lease, "su - " + USER + " -c 'rm -rf ~/sokar && mkdir -p ~/sokar "
                        + "&& tar -x -C ~/sokar -f /tmp/tree.tar'", null);
                run(lease, "su - " + USER + " -c 'cd ~/sokar && "
                        + "JAVA_HOME=" + GRAALVM_HOME + " GRAALVM_HOME=" + GRAALVM_HOME
                        + " PATH=" + GRAALVM_HOME + "/bin:$PATH "
                        + "./mvnw -B -Pnative -DskipTests package "
                        + "-pl app,daemon,hooks,agents/stub -am'", null);
                if ("fedora".equals(os)) {
                    // Compiled here rather than shipped compiled - a .pp is tied to the policy
                    // version of the machine that built it - and while the checkout is still
                    // there. Without sokar_socket a task is denied connectto on its own vault
                    // socket, the denial is dontaudit'ed, and it presents as an agent that cannot
                    // authenticate with nothing in the audit log.
                    System.out.println("loading Sokar's SELinux policy");
                    run(lease, "cd /home/" + USER + "/sokar "
                            + "&& bash selinux/install-selinux-policy.sh", null);
                    run(lease, "semodule -l | grep -qx sokar_socket", null);
                }
                // The tree goes, the dependencies stay: what a leg wants is a warm repository,
                // not somebody else's checkout.
                run(lease, "su - " + USER + " -c 'rm -rf ~/sokar' && rm -f /tmp/tree.tar", null);
            }
            // Stopped first: an image of a running machine is a picture of a half-written disk.
            System.out.println("stopping and taking the image");
            hetzner.shutdown(lease.id());
            final long image = hetzner.snapshot(lease.id(), description, os);
            System.out.println("snapshot " + image + ": " + description);
            return image;
        }
    }

    /**
     * Runs one step, and stops the build when it fails.
     *
     * @param lease The machine.
     * @param command What to run.
     * @param stdin What to feed it, or {@code null}.
     * @throws IOException If the step failed.
     */
    private static void run(Lease lease, String command, String stdin) throws IOException {
        final Ssh.Output out = lease.ssh().run(command, stdin);
        if (out.status() != 0) {
            throw new IOException("preparing the image failed at: " + command + "\n" + out.all());
        }
    }

    /**
     * Returns what to run on a fresh machine to make it one a leg can use.
     *
     * @param os Which operating system.
     * @return A script.
     */
    static String recipe(String os) {
        return TEMPLATE
                .replace("@PACKAGES@", packages(os))
                .replace("@DOWNLOAD@", download())
                .replace("@GRAALVM@", GRAALVM_HOME)
                .replace("@SHA256@", GRAALVM_SHA256)
                .replace("@PULLS@", pulls())
                .replace("@USER@", USER);
    }

    /**
     * Returns what to install from the distribution's own packages.
     * <p>
     * The C toolchain is native-image's rather than podman's: it links what it generates, and
     * GraalVM's prerequisites on Linux are a compiler, the glibc headers and zlib.
     *
     * @param os Which operating system.
     * @return A command.
     */
    private static String packages(String os) {
        if ("fedora".equals(os)) {
            return "dnf install -y -q podman nftables git curl gnupg2 ca-certificates "
                    + "shadow-utils slirp4netns passt fuse-overlayfs crun dnsmasq "
                    + "gcc glibc-devel zlib-devel libstdc++-static && dnf clean all";
        }
        return "export DEBIAN_FRONTEND=noninteractive && apt-get update -qq && "
                + "apt-get install -y -qq podman nftables git curl gnupg ca-certificates "
                + "uidmap slirp4netns passt fuse-overlayfs crun dnsmasq-base "
                + "build-essential zlib1g-dev "
                + "&& apt-get clean && rm -rf /var/lib/apt/lists/*";
    }

    /**
     * Returns where the JDK comes from.
     *
     * @return A download URL.
     */
    private static String download() {
        return "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-"
                + GRAALVM_VERSION + "/graalvm-community-jdk-" + GRAALVM_VERSION
                + "_linux-x64_bin.tar.gz";
    }

    private static String pulls() {
        final StringBuilder out = new StringBuilder();
        for (final String image : IMAGES) {
            out.append("su - ").append(USER).append(" -c 'podman pull -q ").append(image)
                    .append("'\n");
        }
        return out.toString().strip();
    }

    /** What a fresh machine is turned into. Placeholders rather than positional arguments. */
    private static final String TEMPLATE = """
            set -eu
            @PACKAGES@
            curl -fsSL @DOWNLOAD@ -o /tmp/graalvm.tar.gz
            echo '@SHA256@  /tmp/graalvm.tar.gz' | sha256sum -c -
            mkdir -p @GRAALVM@
            tar -xzf /tmp/graalvm.tar.gz -C @GRAALVM@ --strip-components=1
            rm -f /tmp/graalvm.tar.gz
            # Nothing that updates packages in the background. A build is timed, and a machine
            # that decides to fetch security updates five minutes after boot spends a leg's CPU
            # on something nobody asked for. Measured: the same hello-world native image took
            # 7m38s on one boot and 1m08s on the next, same snapshot and same server type.
            systemctl disable --now unattended-upgrades apt-daily.timer apt-daily-upgrade.timer \
                dnf-makecache.timer dnf5-makecache.timer >/dev/null 2>&1 || true
            id -u @USER@ >/dev/null 2>&1 || useradd -m -s /bin/bash @USER@
            loginctl enable-linger @USER@
            install -d -m 0700 -o @USER@ -g @USER@ /home/@USER@/.ssh
            install -m 0600 -o @USER@ -g @USER@ /root/.ssh/authorized_keys \\
                /home/@USER@/.ssh/authorized_keys
            # Pulled as the user that will run them: rootless podman keeps its own store.
            @PULLS@
            # Not merely present: a dnsmasq without nftset support opens nothing while
            # resolving everything, which is a firewall that answers every question and admits
            # nobody - and it fails as a task that cannot reach a host the project declared.
            dnsmasq --version | head -1
            dnsmasq --version | grep -q nftset || { echo 'dnsmasq has no nftset support'; exit 1; }
            echo '### prepared'
            df -h / | tail -1
            @GRAALVM@/bin/java --version | head -1
            su - @USER@ -c 'podman images'
            """;
}
