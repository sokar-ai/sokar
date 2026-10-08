package org.fuin.sokar.legs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.fuin.sokar.machines.Credential;
import org.fuin.sokar.machines.Hetzner;
import org.fuin.sokar.machines.Spec;
import org.fuin.sokar.machines.Ssh;

/**
 * The command line for what only this repository does with a machine: {@code leg}, which builds this tree on a
 * rented machine and runs its acceptance suite there, and {@code deploy}, which installs a handover on a machine
 * somebody keeps. Everything else - the sweep, renting, the pinned JDK - is {@code org.fuin.sokar.machines.Main}.
 */
public final class Main {

    /** Where the Hetzner API token is read from. */
    static final String API_TOKEN = "HETZNER_API";

    /** Where the private key is read from, as material rather than a path. */
    static final String SSH_KEY = "HETZNER_SSH";

    private Main() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs a command.
     *
     * @param args {@code leg ...} or {@code deploy ...}.
     */
    public static void main(String[] args) {
        try {
            System.exit(run(args, COMPLAIN, () -> Hetzner.with(token(System.getenv(API_TOKEN)),
                    runId(System.getenv("GITHUB_RUN_ID"), System.getenv("SOKAR_CI_LEG")))));
        } catch (IOException | IllegalStateException | IllegalArgumentException ex) {
            // The refusals made on purpose; anything else keeps its stack trace.
            COMPLAIN.accept(ex.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args, Consumer<String> complain, Supplier<Hetzner> open) throws IOException {
        if (args.length > 0 && "leg".equals(args[0])) {
            return leg(args, complain, open);
        }
        if (args.length > 0 && "deploy".equals(args[0])) {
            return deploy(args, complain);
        }
        complain.accept(args.length == 0 ? "no command" : "unknown command '" + args[0] + "'");
        System.err.println("""
            Usage: leg      --os <ubuntu|fedora> --repo <dir> [--key <file>] [--keep]
                            [--fetch <dir>] [--acceptance] [--type <t,t...>] [--accounts <n>]
                   deploy   --vm <user@host> --key <file> [--repo <dir>] [--skip-build]
                            [--run <n>] [--account] - builds here and installs on a machine
                            somebody keeps, with lingering on and the daemon restarted. --vm
                            and --key default to SOKAR_VM and SOKAR_VM_KEY. --account installs
                            into that user's own directories only, changing nothing any other
                            account runs.""");
        return 2;
    }

    private static int deploy(String[] args, Consumer<String> complain) throws IOException {
        String vm = System.getenv("SOKAR_VM");
        String key = System.getenv("SOKAR_VM_KEY");
        String repo = ".";
        String run = System.getenv("SOKAR_SNAPSHOT_RUN");
        boolean skipBuild = false;
        Deploy.Scope scope = Deploy.Scope.MACHINE;
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--account" -> scope = Deploy.Scope.ACCOUNT;
                case "--vm" -> vm = value(args, ++at);
                case "--key" -> key = value(args, ++at);
                case "--repo" -> repo = value(args, ++at);
                case "--run" -> run = value(args, ++at);
                case "--skip-build" -> skipBuild = true;
                default -> {
                    complain.accept("unknown option: " + args[at]);
                    return 2;
                }
            }
        }
        if (vm == null || !vm.contains("@") || key == null) {
            complain.accept("deploy needs --vm <user@host> and --key <file>, or SOKAR_VM and SOKAR_VM_KEY");
            return 2;
        }
        final Path root = Path.of(repo).toAbsolutePath().normalize();
        final String user = vm.substring(0, vm.indexOf('@'));
        final String host = vm.substring(vm.indexOf('@') + 1);
        try (Ssh ssh = Ssh.to(host, user, new Credential.InFile(Path.of(key)))) {
            final Deploy.Remote remote = new Deploy.Remote() {
                @Override
                public Ssh.Output run(String command) throws IOException {
                    return ssh.run(command);
                }

                @Override
                public void upload(Path local, String target) throws IOException {
                    ssh.upload(local, target);
                }
            };
            return Deploy.deploy(vm, root, run == null || run.isBlank() ? null : run, skipBuild, scope, remote,
                    number -> build(root, number), System.out);
        }
    }

    /**
     * Builds the packages CI would publish, with the profiles CI uses and a run number of our own.
     *
     * @param root The repository.
     * @param run The run number.
     * @return The build's exit code.
     * @throws IOException If it could not be started.
     */
    private static int build(Path root, String run) throws IOException {
        // -s settings.xml as in CI: the build tooling's snapshots come from there, not from a person's own settings.
        final Process maven = new ProcessBuilder(root.resolve("mvnw").toString(), "-B", "-s", "settings.xml",
                "-Pnative,dist", "verify",
                "-DskipTests", "-Dsokar.snapshot.run=" + run).directory(root.toFile()).inheritIO().start();
        try {
            return maven.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            maven.destroy();
            throw new IOException("interrupted while building", ex);
        }
    }

    /**
     * Runs one test leg against a rented machine.
     *
     * @param args The command line.
     * @param complain Where a refusal is said.
     * @param open Where to rent it.
     * @return An exit code.
     * @throws IOException If the leg fails.
     */
    private static int leg(String[] args, Consumer<String> complain, Supplier<Hetzner> open) throws IOException {
        String os = null;
        String key = null;
        String repo = null;
        String into = null;
        boolean keep = false;
        boolean acceptance = false;
        String types = null;
        int accounts = 1;
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--os" -> os = value(args, ++at);
                case "--key" -> key = value(args, ++at);
                case "--repo" -> repo = value(args, ++at);
                case "--keep" -> keep = true;
                case "--fetch" -> into = value(args, ++at);
                // Features beside each other, one account each; 1 runs them in order, as always.
                case "--accounts" -> {
                    final String count = value(args, ++at);
                    if (!count.matches("[1-9]\\d?")) {
                        complain.accept("--accounts needs a number from 1 to 99, not " + count);
                        return 2;
                    }
                    accounts = Integer.parseInt(count);
                }
                case "--acceptance" -> acceptance = true;
                // Which types to rent, in the order to try them.
                case "--type" -> types = value(args, ++at);
                default -> {
                    complain.accept("unknown option: " + args[at]);
                    return 2;
                }
            }
        }
        if (os == null || repo == null) {
            complain.accept("leg needs --os and --repo");
            return 2;
        }
        try (Hetzner hetzner = open.get()) {
            Leg.run(hetzner, os, types == null ? Spec.DEFAULT_TYPES : Spec.order(types),
                    Credential.of(System.getenv(SSH_KEY), key == null ? null : Path.of(key)),
                    archiveOf(Path.of(repo)), keep, into == null ? null : Path.of(into),
                    acceptance ? Path.of(repo) : null, accounts);
        }
        return 0;
    }

    /**
     * Returns an option's value, which is the next word; refused rather than read as {@code null}.
     *
     * @param args The command line.
     * @param at Where the value should be.
     * @return The value.
     * @throws IllegalArgumentException If there is none.
     */
    static String value(String[] args, int at) {
        if (at >= args.length) {
            throw new IllegalArgumentException("option " + args[at - 1] + " needs a value");
        }
        return args[at];
    }

    /**
     * Returns a tar of what is checked out: {@code git archive} of HEAD, exactly what is here.
     *
     * @param root The repository.
     * @return A temporary tar.
     * @throws IOException If it cannot be made.
     */
    private static Path archiveOf(Path root) throws IOException {
        final Path archive = Files.createTempFile("sokar-tree", ".tar");
        final Process tar = new ProcessBuilder("git", "archive", "--format=tar", "HEAD").directory(root.toFile())
                .redirectOutput(archive.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            if (tar.waitFor() != 0) {
                throw new IOException("could not archive the working tree at " + root);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted archiving the working tree", ex);
        }
        return archive;
    }

    /** A {@code ::error::} line in a workflow, a plain one elsewhere; a test passes its own sink. */
    private static final Consumer<String> COMPLAIN = message -> System.err.println(
            System.getenv("GITHUB_ACTIONS") == null ? "sokar: " + message : "::error::" + message);

    /**
     * Returns the API token, or refuses when there is none. Taken as a parameter, never read here, so a test can
     * say what happens when it is absent.
     *
     * @param fromEnvironment What the environment holds, or {@code null}.
     * @return The token.
     */
    static String token(String fromEnvironment) {
        if (fromEnvironment == null || fromEnvironment.isBlank()) {
            throw new IllegalStateException("No API token: set " + API_TOKEN + " in the environment.");
        }
        return fromEnvironment;
    }

    /**
     * Returns what identifies this run: the workflow run in CI, a timestamp locally, with the leg in both, so a
     * machine on the shared account can be told apart and attributed.
     *
     * @param run The workflow run, or {@code null} outside CI.
     * @param leg Which leg of the matrix, or {@code null}.
     * @return The run id.
     */
    static String runId(String run, String leg) {
        final String named = leg == null || leg.isBlank() ? "" : "-" + leg;
        if (run != null && !run.isBlank()) {
            return run + named;
        }
        return "local-" + System.currentTimeMillis() + named;
    }
}
