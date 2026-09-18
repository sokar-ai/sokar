package org.fuin.sokar.machines;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An agent's acceptance leg: install the packages as an operator would, then see what a person
 * gets.
 * <p>
 * <strong>Here rather than in each agent's repository.</strong> Three repositories had three
 * copies of this and of the API helpers under it, and they had already drifted - one grew a
 * key-cleaning step the others lacked, so the same secret was cleaned in two places and passed
 * through raw in the third.
 * <p>
 * <strong>Installed from the repository, not unpacked.</strong> The package comes from
 * Artifactory in the same command as {@code sokar}, so the repository, its signature, the index
 * and {@code Depends: sokar} are all exercised. A candidate package - one built in this run and
 * published nowhere - is installed by path in that same command for the same reason: neither
 * {@code dpkg -i} nor {@code rpm -i} resolves a dependency, and installing {@code sokar}
 * separately by hand would prove less than an operator's own package manager does.
 */
public final class AgentLeg {

    /** Which stock image each operating system starts from: no snapshot, this installs its own. */
    private static final Map<String, String> IMAGES = Map.of(
            "ubuntu", "ubuntu-26.04", "fedora", "fedora-44");

    /** Which suffix a candidate package has, per operating system. */
    private static final Map<String, String> CANDIDATE = Map.of(
            "ubuntu", ".deb", "fedora", ".rpm");

    /** The unprivileged user a suite runs as: the shape a task runs in, rootless. */
    private static final String USER = "acceptance";

    /**
     * What reaches the far shell as environment rather than as arguments.
     * <p>
     * {@code argv} is readable by every process on that machine. {@code SOKAR_E2E_EXPECT_CLI} is
     * not a credential and rides here only because it goes to the same place.
     */
    private static final List<String> PASSED = List.of(
            "SOKAR_E2E_OPENROUTER_API_KEY", "SOKAR_E2E_MODEL", "SOKAR_E2E_EXPECT_CLI");

    private AgentLeg() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * What one leg needs to know.
     *
     * @param os Which operating system.
     * @param types Server types to try, in order.
     * @param artifactory Where the packages are published.
     * @param packageName The agent package, such as {@code sokar-agent-claude}.
     * @param script The acceptance script to run on the machine.
     * @param candidate A directory holding a package built in this run, or {@code null} to
     *     install the published one.
     * @param keep Whether to leave the machine running.
     * @param cucumber The repository to run the Cucumber suite from, or {@code null} not to.
     */
    public record Options(String os, List<String> types, String artifactory, String packageName,
            String script, Path candidate, boolean keep, Path cucumber) {
    }

    /**
     * Runs one acceptance leg.
     *
     * @param hetzner Where the machine comes from - rented, or already here.
     * @param options What to do.
     * @param credential The key to connect with.
     * @throws IOException If any step fails, saying which.
     */
    public static void run(Machines hetzner, Options options, Credential credential)
            throws IOException {
        final String image = IMAGES.get(options.os());
        if (image == null) {
            throw new IOException("No image for '" + options.os() + "'. Known: " + IMAGES.keySet());
        }
        final Spec spec = new Spec("sokar-acc-" + options.os() + "-" + hetzner.runId(),
                options.os(), options.types(), "root", credential, options.keep());
        try (Lease lease = hetzner.acquireFromStock(spec, image)) {
            lease.awaitSsh();

            String installs = options.packageName();
            if (options.candidate() != null) {
                installs = sendCandidate(lease, options);
            }

            System.out.println("\n-- installing from " + options.artifactory()
                    + ", as an operator would --");
            run(lease, install(options.os(), options.artifactory(), installs));

            System.out.println("\n-- creating the " + USER + " user --");
            run(lease, "id -u " + USER + " >/dev/null 2>&1 || useradd -m -s /bin/bash " + USER
                    + "; loginctl enable-linger " + USER
                    + "; install -d -m 0700 -o " + USER + " -g " + USER + " /home/" + USER + "/.ssh"
                    + " && install -m 0600 -o " + USER + " -g " + USER
                    + " /root/.ssh/authorized_keys /home/" + USER + "/.ssh/authorized_keys");

            // As USER, not as the root connection above. The suite has to run in the shape a
            // task runs in - rootless podman, one operator's own directories - and root has
            // neither: podman is rootful there and /run/user/0 does not exist.
            try (Ssh user = Ssh.to(lease.address(), USER, credential)) {
                System.out.println("\n-- sending the suite --");
                run(user, "cat > /home/" + USER + "/acceptance.sh && chmod +x /home/" + USER
                        + "/acceptance.sh", options.script());

                System.out.println("\n-- acceptance --");
                run(user, "cd /home/" + USER + " && XDG_RUNTIME_DIR=/run/user/$(id -u) "
                        + exported() + "./acceptance.sh", null);
            }

            if (options.cucumber() != null) {
                // From this end rather than on the server: it drives a terminal over ssh, so what
                // is being simulated is somebody sitting here - and this is also where GITHUB_*
                // exists, so a failure lands as an annotation on the feature file.
                System.out.println("\n-- acceptance, as a person at a terminal --");
                cucumber(options.cucumber(), lease.address(), credential);
            }

            System.out.println("\n-- the leg passed on " + options.os() + " at "
                    + lease.address());
        }
    }

    /**
     * Runs the agent's own Cucumber suite from here against the machine.
     *
     * @param repository Where to run Maven.
     * @param address The machine.
     * @param credential The key the suite connects with, as material rather than a path.
     * @throws IOException If the suite fails.
     */
    private static void cucumber(Path repository, String address, Credential credential)
            throws IOException {
        final ProcessBuilder maven = new ProcessBuilder("./mvnw", "-B", "-s", "settings.xml",
                "verify", "-Dsokar.acceptance.host=" + address,
                "-Dsokar.acceptance.user=" + USER)
                .directory(repository.toFile()).inheritIO();
        maven.environment().put("SOKAR_ACCEPTANCE_KEY", credential.material());
        final int status;
        try {
            status = maven.start().waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running the Cucumber suite", ex);
        }
        if (status != 0) {
            throw new IOException("the Cucumber suite failed");
        }
    }

    /**
     * Puts the packages built in this run on the machine, refusing anything ambiguous.
     * <p>
     * <strong>Several packages are allowed; two builds of the same one are not.</strong> A
     * repository may produce a set that only makes sense together - a filter and a transport, a
     * tool and its agent - and installing them in one command is what proves their dependencies
     * resolve. What is refused is two files naming the same package, because a working tree
     * accumulates those and letting the package manager pick whichever it prefers proves nothing.
     *
     * @param lease The machine.
     * @param options What to do.
     * @return How the package manager should name them, space separated.
     * @throws IOException If the directory holds no package, or two builds of one.
     */
    private static String sendCandidate(Lease lease, Options options) throws IOException {
        final String suffix = CANDIDATE.get(options.os());
        final List<Path> built = new ArrayList<>();
        try (var found = Files.list(options.candidate())) {
            found.filter(each -> each.getFileName().toString().endsWith(suffix))
                    .sorted().forEach(built::add);
        }
        if (built.isEmpty()) {
            throw new IOException("--candidate " + options.candidate() + " holds no " + suffix
                    + " package. Installing the published package instead would look exactly like"
                    + " a passing run, which is why this stops.");
        }
        final Map<String, Path> byName = new LinkedHashMap<>();
        for (final Path each : built) {
            final String name = packageName(each.getFileName().toString(), suffix);
            final Path already = byName.put(name, each);
            if (already != null) {
                throw new IOException("--candidate " + options.candidate() + " holds two builds of"
                        + " '" + name + "': " + already.getFileName() + " and "
                        + each.getFileName() + ". The package manager would take whichever it"
                        + " prefers - clean the directory.");
            }
        }

        System.out.println("\n-- sending the candidate: " + byName.keySet() + " --");
        run(lease, "mkdir -p /root/candidate && rm -f /root/candidate/*");
        final StringBuilder remote = new StringBuilder();
        for (final Path each : byName.values()) {
            final String at = "/root/candidate/" + each.getFileName();
            lease.ssh().upload(each, at);
            remote.append(remote.isEmpty() ? "" : " ").append(at);
        }
        return remote.toString();
    }

    /**
     * Returns the package a built file belongs to.
     * <p>
     * By the file name, which is the only thing available before the file is on a machine that
     * has the tools to read it. Both formats put the name first and separate it from the version
     * the same way every time: {@code name_version_arch.deb} and
     * {@code name-version-release.arch.rpm}.
     *
     * @param file The file name.
     * @param suffix {@code .deb} or {@code .rpm}.
     * @return The package name, or the whole file name when it does not have that shape - which
     *         makes such a file its own package rather than silently grouping it with another.
     */
    static String packageName(String file, String suffix) {
        final String stem = file.endsWith(suffix)
                ? file.substring(0, file.length() - suffix.length()) : file;
        if (".deb".equals(suffix)) {
            final int underscore = stem.indexOf('_');
            return underscore > 0 ? stem.substring(0, underscore) : stem;
        }
        // name-version-release.arch: drop the last two dash-separated parts, and only those.
        final int release = stem.lastIndexOf('-');
        final int version = release > 0 ? stem.lastIndexOf('-', release - 1) : -1;
        return version > 0 ? stem.substring(0, version) : stem;
    }

    /**
     * Returns the environment a credential travels in.
     *
     * @return Assignments to prefix a command with, empty when nothing is set here.
     */
    private static String exported() {
        final StringBuilder out = new StringBuilder();
        for (final String name : PASSED) {
            final String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                out.append(name).append('=').append(quote(value)).append(' ');
            }
        }
        return out.toString();
    }

    /**
     * Quotes a value so a shell takes it whole.
     *
     * @param value What to quote.
     * @return The value, single quoted.
     */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Returns how a distribution installs Sokar and the agent, in one command.
     *
     * @param os Which operating system.
     * @param artifactory Where the packages are published.
     * @param installs The agent package, by name or by path.
     * @return A script.
     */
    static String install(String os, String artifactory, String installs) {
        if ("fedora".equals(os)) {
            return """
                set -eux
                cat > /etc/yum.repos.d/sokar.repo <<'EOF'
                [sokar]
                name=Sokar
                baseurl=@BASE@/sokar-dist-rpm/snapshots
                enabled=1
                gpgcheck=0
                EOF
                dnf install -y -q podman
                dnf install -y -q sokar @PACKAGE@
                """.replace("@BASE@", artifactory).replace("@PACKAGE@", installs);
        }
        return """
            set -eux
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -qq
            apt-get install -y -qq ca-certificates curl gnupg podman
            curl -fsSL @KEY@ | gpg --dearmor > /usr/share/keyrings/sokar.gpg
            echo "deb [signed-by=/usr/share/keyrings/sokar.gpg] @BASE@/sokar-dist-deb snapshots main" \\
                > /etc/apt/sources.list.d/sokar.list
            apt-get update
            apt-get install -y -qq sokar @PACKAGE@
            """.replace("@KEY@", artifactory + "/api/security/keypair/sokar-packages/public")
                .replace("@BASE@", artifactory).replace("@PACKAGE@", installs);
    }

    private static void run(Lease lease, String command) throws IOException {
        run(lease.ssh(), command, null);
    }

    private static void run(Lease lease, String command, String stdin) throws IOException {
        run(lease.ssh(), command, stdin);
    }

    private static void run(Ssh ssh, String command, String stdin) throws IOException {
        final Ssh.Output out = ssh.run(command, stdin);
        System.out.print(out.all());
        if (out.status() != 0) {
            throw new IOException("the leg failed at: " + command);
        }
    }
}
