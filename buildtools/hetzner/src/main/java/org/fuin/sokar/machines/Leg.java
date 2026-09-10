package org.fuin.sokar.machines;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
     * @throws IOException If any step fails, saying which.
     */
    public static void run(Hetzner hetzner, String os, List<String> types, Credential credential,
            Path archive, boolean keep) throws IOException {
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

            step("installing as a package would");
            run(lease, "mkdir -p ~/.local/bin ~/.local/share/sokar/agents "
                    + "~/.local/share/sokar/providers && cp " + REPO + "/hooks/target/sokar-hook-* "
                    + "~/.local/bin/");

            step("what sokar thinks of this machine");
            // Not fatal: doctor reports, and a leg that stops here would hide the run below.
            System.out.println(lease.ssh().run("cd " + REPO
                    + " && PATH=$HOME/.local/bin:$PATH sokar doctor 2>&1 "
                    + "|| echo '(sokar doctor failed)'").all().strip());

            step("tier 1, on " + os);
            run(lease, "cd " + REPO + " && PATH=$HOME/.local/bin:$PATH bash buildtools/e2e-tier1.sh");

            System.out.println("\n-- the leg passed on " + os + " at " + lease.address());
            if (keep) {
                System.out.println("-- kept, so it can be looked at; it is billing until swept");
            }
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
