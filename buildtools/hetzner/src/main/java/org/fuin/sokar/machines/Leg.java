package org.fuin.sokar.machines;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * One test leg: rent a machine, build the product on it, and see what it does.
 * <p>
 * <strong>The same steps wherever it is driven from.</strong> CI runs this against a rented
 * machine and so can a developer, which is the point - when the build minutes run out, or a
 * failure needs a second look, the leg is not something only a workflow can perform.
 * <p>
 * <strong>Nothing here decides what to test.</strong> The build command, the installation and the
 * end-to-end script are the repository's, run as they are; this class rents the machine, puts the
 * tree on it, and reports. A driver that quietly did something different from CI would be worse
 * than no driver.
 */
public final class Leg {

    /** Where the working tree goes on the machine. */
    private static final String REPO = "/home/build/sokar";

    /** The user a leg runs as: unprivileged, because that is the shape a task runs in. */
    private static final String USER = "build";

    /** How the product is built, named once and identical to what CI runs. */
    private static final String BUILD =
            "cd " + REPO + " && JAVA_HOME=/opt/graalvm GRAALVM_HOME=/opt/graalvm "
            + "PATH=/opt/graalvm/bin:$PATH ./mvnw -B -Pnative -DskipTests package "
            + "-pl app,daemon,hooks,agents/stub -am";

    /**
     * What a leg sends home, and what the publish job installs.
     * <p>
     * The stub agent is packaged so that the package checks still have an agent package to
     * install and can prove {@code Depends: sokar} resolves. It is never published.
     */
    private static final List<String> BINARIES = List.of(
            "app/target/sokar",
            "daemon/target/sokard",
            "hooks/target/sokar-hook-nft",
            "hooks/target/sokar-hook-supervisor",
            "hooks/target/sokar-hook-reader",
            "agents/stub/target/sokar-agent-stub");

    /** Names what came back, so the publish job can refuse a leg that sent nothing. */
    private static final String MANIFEST = "fetched.txt";

    private Leg() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs a leg.
     *
     * @param hetzner Where to rent the machine.
     * @param os Which operating system's snapshot to boot.
     * @param types Server types to try, in order.
     * @param credential The key to connect with.
     * @param archive A tar of the working tree.
     * @param keep Whether to leave the machine running, to look at a failure.
     * @param into Where to put the built binaries, or {@code null} to leave them on the machine.
     * @param suite The repository to run the acceptance suite from, or {@code null} not to.
     * @throws IOException If any step fails, saying which.
     */
    public static void run(Hetzner hetzner, String os, List<String> types, Credential credential,
            Path archive, boolean keep, Path into, Path suite) throws IOException {
        final Spec spec = new Spec("sokar-leg-" + os + "-" + System.currentTimeMillis() / 1000,
                os, types, USER, credential, keep);
        try (Lease lease = hetzner.acquire(spec)) {
            lease.awaitSsh();

            step("sending the working tree");
            lease.ssh().upload(archive, "/tmp/tree.tar");
            run(lease, "rm -rf " + REPO + " && mkdir -p " + REPO
                    + " && tar -x -C " + REPO + " -f /tmp/tree.tar && rm -f /tmp/tree.tar");

            step("building");
            run(lease, BUILD);

            // Entirely in the user's own directories, with no sudo. Sokar scans
            // ~/.local/share/sokar/providers before /usr/share and resolves hooks from
            // ~/.local/bin before /usr/libexec, so an unprivileged install is a supported shape
            // rather than a shortcut - and it is the shape a task actually runs in: rootless.
            //
            // All four copies matter. An earlier version of this driver left out the providers
            // and the binary, and the leg failed with "No provider 'anthropic' is declared" -
            // which reads like a broken machine and was a broken transcription.
            step("installing as a package would");
            run(lease, "mkdir -p ~/.local/bin ~/.local/share/sokar/agents "
                    + "~/.local/share/sokar/providers"
                    + " && cp " + REPO + "/hooks/target/sokar-hook-* ~/.local/bin/"
                    + " && cp " + REPO + "/app/target/sokar ~/.local/bin/"
                    + " && cp " + REPO + "/providers/*.yaml ~/.local/share/sokar/providers/"
                    + " && ~/.local/bin/sokar setup");

            step("what sokar thinks of this machine");
            // The podman version too: it decides whether this leg is really covering podman 4 or
            // has quietly become a second Fedora. Not fatal - doctor reports, and stopping here
            // would hide the run below.
            System.out.println(lease.ssh().run("podman --version; cd " + REPO
                    + " && PATH=$HOME/.local/bin:$PATH sokar doctor 2>&1 "
                    + "|| echo '(sokar doctor failed)'").all().strip());

            step("tier 1, on " + os);
            run(lease, "cd " + REPO + " && PATH=$HOME/.local/bin:$PATH bash buildtools/e2e-tier1.sh");

            if (into != null) {
                step("fetching the binaries");
                fetch(lease, into);
            }

            if (suite != null) {
                step("what a person does at a terminal");
                acceptance(suite, lease.address(), credential);
            }

            System.out.println("\n-- the leg passed on " + os + " at " + lease.address());
            if (keep) {
                System.out.println("-- kept, so it can be looked at; it is billing until swept");
            }
        }
    }

    /**
     * Runs the Cucumber suite from here against the machine.
     * <p>
     * From here rather than on the server because what is being simulated is somebody sitting
     * here: the suite drives a terminal over ssh. A second machine would double what a merge
     * costs to prove the same binary.
     * <p>
     * The key is handed over as material rather than as a path, for the reason
     * {@link Credential} records: writing it to a file made its exact bytes matter, and a stray
     * carriage return failed as "error in libcrypto" naming neither the file nor the reason.
     *
     * @param repository Where to run Maven.
     * @param address The machine.
     * @param credential The key the suite connects with.
     * @throws IOException If the suite fails, or proves nothing.
     */
    private static void acceptance(Path repository, String address, Credential credential)
            throws IOException {
        final ProcessBuilder maven = new ProcessBuilder("./mvnw", "-B",
                "-pl", "acceptance/suite", "-am", "verify", "-s", "settings.xml",
                "-Dsokar.acceptance.host=" + address,
                "-Dsokar.acceptance.user=" + USER,
                // The suite would otherwise look for a key file that CI deliberately does not have.
                "-Dsokar.acceptance.key=unused-the-material-is-in-the-environment",
                // Nothing that needs a task image: those are minutes each and a leg already
                // built one.
                "-Dcucumber.filter.tags=not @slow")
                .directory(repository.toFile()).inheritIO();
        maven.environment().put("SOKAR_ACCEPTANCE_KEY", credential.material());
        final int status;
        try {
            status = maven.start().waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running the acceptance suite", ex);
        }
        if (status != 0) {
            throw new IOException("the acceptance suite failed");
        }
        proved(repository.resolve("acceptance/suite/target/failsafe-reports"));
    }

    /**
     * Brings the built binaries back, keeping their paths.
     * <p>
     * One archive over the connection that is already open, rather than six transfers or a second
     * way of presenting the key. Every name is checked on arrival: a publish job that installed
     * five of six binaries and said nothing would be worse than one that failed.
     *
     * @param lease The machine.
     * @param into Local directory to extract under.
     * @throws IOException If anything did not come back.
     */
    private static void fetch(Lease lease, Path into) throws IOException {
        Files.createDirectories(into);
        final Ssh.Output made = lease.ssh().run("cd " + REPO + " && tar -czf /tmp/binaries.tar.gz "
                + String.join(" ", BINARIES));
        if (made.status() != 0) {
            throw new IOException("could not pack the binaries: " + made.all());
        }
        final Path archive = into.resolve("binaries.tar.gz");
        lease.ssh().download("/tmp/binaries.tar.gz", archive);
        final Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(),
                "-C", into.toString()).inheritIO().start();
        try {
            if (tar.waitFor() != 0) {
                throw new IOException("could not unpack " + archive);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted unpacking the binaries", ex);
        }
        Files.delete(archive);
        final StringBuilder manifest = new StringBuilder();
        for (final String name : BINARIES) {
            final Path came = into.resolve(name);
            if (!Files.isRegularFile(came)) {
                throw new IOException(name + " did not come back from the server");
            }
            came.toFile().setExecutable(true, false);
            System.out.println("  " + name + "  " + Files.size(came) / 1024 + " KiB");
            manifest.append(name).append('\n');
        }
        Files.writeString(into.resolve(MANIFEST), manifest.toString());
    }

    /**
     * Fails when the suite produced no results at all.
     * <p>
     * A suite that selects nothing passes, and a page with no acceptance section looks exactly
     * like one where the step was never added. This repository sat in that state for several
     * merges after a module split left the suite out of the reactor, and nothing said so.
     *
     * @param reports Where failsafe writes its XML.
     * @throws IOException If nothing ran.
     */
    static void proved(Path reports) throws IOException {
        int total = 0;
        if (Files.isDirectory(reports)) {
            try (var found = Files.list(reports)) {
                for (final Path report : found.filter(each ->
                        each.getFileName().toString().startsWith("TEST-")
                                && each.getFileName().toString().endsWith(".xml")).toList()) {
                    total += tests(report);
                }
            }
        }
        if (total == 0) {
            throw new IOException("the acceptance suite ran no scenarios - nothing in " + reports
                    + ". Check that the suite is in the reactor and that the tag filter selects"
                    + " something.");
        }
    }

    private static int tests(Path report) throws IOException {
        try {
            final var document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(report.toFile());
            final String said = document.getDocumentElement().getAttribute("tests");
            return said.isBlank() ? 0 : Integer.parseInt(said);
        } catch (Exception ex) {
            throw new IOException("could not read " + report, ex);
        }
    }

    private static void step(String what) {
        System.out.println("\n-- " + what + " --");
    }

    /**
     * Runs one step, and stops the leg where it failed rather than carrying on.
     *
     * @param lease The machine.
     * @param command What to run.
     * @throws IOException If it failed.
     */
    private static void run(Lease lease, String command) throws IOException {
        final Ssh.Output out = lease.ssh().run(command);
        System.out.print(out.all());
        if (out.status() != 0) {
            throw new IOException("the leg failed at: " + command);
        }
    }
}
