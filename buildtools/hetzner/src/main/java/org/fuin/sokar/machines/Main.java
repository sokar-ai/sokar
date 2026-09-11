package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The command line, for the parts of this a workflow calls directly.
 * <p>
 * Only the sweep is here. Renting a machine is not a command, because a command that creates a
 * server and returns leaves the deleting to whoever remembers - which is the failure this whole
 * module exists to make structural. Callers that need a machine hold a {@link Lease} for as long
 * as they need it.
 */
public final class Main {

    /**
     * How old a server must be before a sweep counts it as forgotten.
     * <p>
     * Minutes, and the same default as the script this replaces. A unit that differs between the
     * two would have been read straight off a workflow line - {@code --older-than 60} means an
     * hour there and would have meant sixty hours here.
     */
    private static final Duration DEFAULT_AGE = Duration.ofMinutes(60);

    private Main() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Where the Hetzner API token is read from. Named once: a half-done rename is silent. */
    static final String API_TOKEN = "HETZNER_API";

    /** Where the private key is read from, as material rather than a path. */
    static final String SSH_KEY = "HETZNER_SSH";

    /**
     * Runs a sweep.
     *
     * @param args {@code sweep} with {@code --mine}, {@code --now}, {@code --older-than <hours>}
     *     or nothing, which lists what would go.
     */
    public static void main(String[] args) {
        try {
            System.exit(run(args, COMPLAIN, () -> Hetzner.with(
                    token(System.getenv(API_TOKEN)), runId(System.getenv("GITHUB_RUN_ID"),
                            System.getenv("SOKAR_CI_LEG")))));
        } catch (IOException | IllegalStateException | IllegalArgumentException ex) {
            // The refusals this code makes on purpose - a missing token, an API that said no.
            // Anything else is a fault here and keeps its stack trace, because a one-line
            // message for a NullPointerException hides the only useful thing about it.
            COMPLAIN.accept(ex.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args, Consumer<String> complain, Supplier<Hetzner> open) throws IOException {
        if (args.length > 0 && "snapshot".equals(args[0])) {
            return snapshot(args, complain, open);
        }
        if (args.length > 0 && "leg".equals(args[0])) {
            return leg(args, complain, open);
        }
        if (args.length > 0 && "acceptance".equals(args[0])) {
            return acceptance(args, complain, open);
        }
        if (args.length > 0 && "lease".equals(args[0])) {
            return lease(args, complain, open);
        }
        if (args.length > 0 && !"sweep".equals(args[0])) {
            // Named rather than answered with the usage text alone. A repository that resolves
            // this from a published snapshot can be handed a build older than the command it is
            // asking for, and a bare usage dump reads as a mistake in the workflow rather than
            // as tooling that has not caught up.
            complain.accept("unknown command '" + args[0] + "'. This build of the tooling knows"
                    + " sweep, snapshot, leg and acceptance - if you expected another, it is"
                    + " older than the caller.");
        }
        if (args.length == 0 || !"sweep".equals(args[0])) {
            System.err.println("""
                Usage: sweep [--mine | --from <file>] [--now] [--older-than <minutes>]
                       snapshot --os <ubuntu|fedora> [--key <file>] [--repo <dir>] [--type <t>]
                       leg      --os <ubuntu|fedora> --repo <dir> [--key <file>] [--keep]
                                [--fetch <dir>] [--acceptance]
                       acceptance --package <p> --script <f> [--os <o>] [--type <t>]
                                [--candidate <dir>] [--cucumber <dir>] [--keep]
                       lease    --os <ubuntu|fedora> [--key <file>] [--write <file>]
                                [--type <t>] - rents a machine, installs Sokar, starts the
                                daemon as an unprivileged user, and leaves it running. What
                                deletes it is 'sweep --mine'.

                  --mine                 delete what this run created, whatever its age. What a
                                         job uses to clean up after itself - deleting by age
                                         catches another run's server when that run is slow
                  --now                  delete, rather than saying what would be deleted
                  --older-than <minutes> age at which a server counts as forgotten (default 60)
                  (none)                 say what would be deleted, and delete nothing

                The API token is read from the environment, never from an argument: everything on
                a command line is readable by every process on the machine.""");
            return 2;
        }

        boolean mine = false;
        String from = null;
        boolean delete = false;
        Duration olderThan = DEFAULT_AGE;
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--mine" -> mine = true;
                case "--from" -> from = at + 1 < args.length ? args[++at] : null;
                case "--now" -> delete = true;
                case "--older-than" -> {
                    if (at + 1 >= args.length) {
                        complain.accept("--older-than needs a number of minutes");
                        return 2;
                    }
                    try {
                        olderThan = Duration.ofMinutes(Long.parseLong(args[++at]));
                    } catch (NumberFormatException ex) {
                        complain.accept("--older-than needs a number of minutes, not '"
                                + args[at] + "'");
                        return 2;
                    }
                }
                default -> {
                    complain.accept("unknown option: " + args[at]);
                    return 2;
                }
            }
        }

        try (Hetzner hetzner = open.get()) {
            if (mine || from != null) {
                // Always deletes, whatever else was passed: a job cleaning up after itself is
                // not a question, and age does not come into it. --from is the same act for a run
                // whose id this process could not derive, so it belongs on this side of the
                // branch - it was nested under --mine when first written, which made the usage
                // text say 'either' while the code meant 'both'.
                if (from != null) {
                    // The run id a lease wrote down, for the case its own could not be derived
                    // again. Reading the file rather than taking an id on the command line: the
                    // caller then has one thing to pass between two steps instead of five.
                    final java.util.Properties leased = new java.util.Properties();
                    try (var in = java.nio.file.Files.newInputStream(
                            java.nio.file.Path.of(from))) {
                        leased.load(in);
                    }
                    final String run = leased.getProperty("run");
                    if (run == null || run.isBlank()) {
                        complain.accept(from + " names no run, so there is nothing to match on."
                                + " It should have been written by 'lease --write'.");
                        return 2;
                    }
                    System.out.println("deleted " + hetzner.deleteRun(run) + " server(s) of "
                            + run);
                } else {
                    System.out.println("deleted " + hetzner.deleteMine()
                            + " of this run's servers");
                }
                return 0;
            }
            final int swept = hetzner.sweep(olderThan, !delete);
            System.out.println((delete ? "deleted " : "would delete ") + swept + " server(s)");
            if (swept > 0 && !delete) {
                // The same exit the script uses, so a scheduled run that finds something without
                // being allowed to remove it is visible rather than quietly green.
                System.out.println("\nnothing was deleted - pass --now");
                return 1;
            }
            return 0;
        }
    }

    /**
     * Builds a snapshot for one operating system.
     *
     * @param args The command line.
     * @param open Where to build it.
     * @return An exit code.
     * @throws IOException If it cannot be built.
     */
    private static int snapshot(String[] args, Consumer<String> complain,
            Supplier<Hetzner> open) throws IOException {
        String os = null;
        String key = null;
        String repo = null;
        String type = null;
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--os" -> os = at + 1 < args.length ? args[++at] : null;
                case "--key" -> key = at + 1 < args.length ? args[++at] : null;
                case "--repo" -> repo = at + 1 < args.length ? args[++at] : null;
                case "--type" -> type = at + 1 < args.length ? args[++at] : null;
                default -> {
                    complain.accept("unknown option: " + args[at]);
                    return 2;
                }
            }
        }
        if (os == null) {
            complain.accept("snapshot needs --os");
            return 2;
        }
        final Credential credential = Credential.of(System.getenv(SSH_KEY),
                key == null ? null : java.nio.file.Path.of(key));
        java.nio.file.Path archive = null;
        String musl = null;
        if (repo != null) {
            final java.nio.file.Path root = java.nio.file.Path.of(repo);
            archive = archiveOf(root);
            musl = java.nio.file.Files.readString(root.resolve("buildtools/install-musl.sh"));
        }
        try (Hetzner hetzner = open.get()) {
            // A named type wins over the small-disk preference. The floor an image gets is the
            // disk it was built on, and a leg rents a 320 GB machine anyway - so when the wait
            // matters more than the floor, say so rather than waiting on two cores.
            Snapshots.build(hetzner, os, type == null ? Snapshots.BUILD_TYPES : List.of(type),
                    credential, archive, musl);
        }
        return 0;
    }

    /**
     * Runs an agent's acceptance leg.
     *
     * @param args The command line.
     * @param complain Where a refusal goes.
     * @param open Where to rent the machine.
     * @return An exit code.
     * @throws IOException If the leg fails.
     */
    private static int acceptance(String[] args, Consumer<String> complain,
            Supplier<Hetzner> open) throws IOException {
        String os = "ubuntu";
        String key = null;
        String pkg = null;
        String script = null;
        String candidate = null;
        String cucumber = null;
        String type = "cpx12";
        String artifactory = "https://fuinorg.jfrog.io/artifactory";
        boolean keep = false;
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--os" -> os = at + 1 < args.length ? args[++at] : null;
                case "--key" -> key = at + 1 < args.length ? args[++at] : null;
                case "--package" -> pkg = at + 1 < args.length ? args[++at] : null;
                case "--script" -> script = at + 1 < args.length ? args[++at] : null;
                case "--candidate" -> candidate = at + 1 < args.length ? args[++at] : null;
                case "--cucumber" -> cucumber = at + 1 < args.length ? args[++at] : null;
                case "--type" -> type = at + 1 < args.length ? args[++at] : null;
                case "--artifactory" -> artifactory = at + 1 < args.length ? args[++at] : null;
                case "--keep" -> keep = true;
                default -> {
                    complain.accept("unknown option: " + args[at]);
                    return 2;
                }
            }
        }
        if (pkg == null || script == null) {
            complain.accept("acceptance needs --package and --script");
            return 2;
        }
        final AgentLeg.Options options = new AgentLeg.Options(os, List.of(type), artifactory, pkg,
                java.nio.file.Files.readString(java.nio.file.Path.of(script)),
                candidate == null ? null : java.nio.file.Path.of(candidate), keep,
                cucumber == null ? null : java.nio.file.Path.of(cucumber));
        try (Hetzner hetzner = open.get()) {
            AgentLeg.run(hetzner, options, Credential.of(System.getenv(SSH_KEY),
                    key == null ? null : java.nio.file.Path.of(key)));
        }
        return 0;
    }

    /**
     * Rents a machine and leaves it running, recording what it took.
     *
     * @param args {@code lease --os <os> [--key <file>] [--write <file>] [--type <t>]}.
     * @param complain Where a refusal goes.
     * @param open How to reach the provider.
     * @return Exit code.
     * @throws IOException If a step fails.
     */
    private static int lease(String[] args, Consumer<String> complain,
            Supplier<Hetzner> open) throws IOException {
        String os = "ubuntu";
        String key = null;
        String write = null;
        String type = "cpx12";
        String artifactory = "https://fuinorg.jfrog.io/artifactory";
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--os" -> os = at + 1 < args.length ? args[++at] : null;
                case "--key" -> key = at + 1 < args.length ? args[++at] : null;
                case "--write" -> write = at + 1 < args.length ? args[++at] : null;
                case "--type" -> type = at + 1 < args.length ? args[++at] : null;
                case "--artifactory" -> artifactory = at + 1 < args.length ? args[++at] : null;
                default -> {
                    complain.accept("unknown option: " + args[at]);
                    return 2;
                }
            }
        }
        final Rental.Options options = new Rental.Options(os, List.of(type), artifactory,
                write == null ? null : java.nio.file.Path.of(write));
        try (Hetzner hetzner = open.get()) {
            Rental.run(hetzner, options, Credential.of(System.getenv(SSH_KEY),
                    key == null ? null : java.nio.file.Path.of(key)));
        }
        return 0;
    }

    /**
     * Returns a tar of what is checked out.
     * <p>
     * {@code git archive} of the working tree rather than a clone: it sends exactly what is here,
     * which is what somebody testing a change needs.
     *
     * @param root The repository.
     * @return A temporary tar, which the caller may leave for the system to clean up.
     * @throws IOException If it cannot be made.
     */
    private static java.nio.file.Path archiveOf(java.nio.file.Path root) throws IOException {
        final java.nio.file.Path archive =
                java.nio.file.Files.createTempFile("sokar-tree", ".tar");
        final Process tar = new ProcessBuilder("git", "archive", "--format=tar", "HEAD")
                .directory(root.toFile())
                .redirectOutput(archive.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
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

    /**
     * Runs one test leg against a rented machine.
     *
     * @param args The command line.
     * @param open Where to rent it.
     * @return An exit code.
     * @throws IOException If the leg fails.
     */
    private static int leg(String[] args, Consumer<String> complain,
            Supplier<Hetzner> open) throws IOException {
        String os = null;
        String key = null;
        String repo = null;
        String into = null;
        boolean keep = false;
        boolean acceptance = false;
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--os" -> os = at + 1 < args.length ? args[++at] : null;
                case "--key" -> key = at + 1 < args.length ? args[++at] : null;
                case "--repo" -> repo = at + 1 < args.length ? args[++at] : null;
                case "--keep" -> keep = true;
                case "--fetch" -> into = at + 1 < args.length ? args[++at] : null;
                case "--acceptance" -> acceptance = true;
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
            Leg.run(hetzner, os, Spec.DEFAULT_TYPES, Credential.of(System.getenv(SSH_KEY),
                    key == null ? null : java.nio.file.Path.of(key)),
                    archiveOf(java.nio.file.Path.of(repo)), keep,
                    into == null ? null : java.nio.file.Path.of(into),
                    acceptance ? java.nio.file.Path.of(repo) : null);
        }
        return 0;
    }

    /**
     * Where a complaint goes when this is run as a command.
     * <p>
     * <strong>Supplied by the caller, not chosen here.</strong> The {@code ::error::} prefix is a
     * workflow command and GitHub turns any line carrying one into an annotation - including a
     * line a unit test caused while checking that a bad option is refused. Gating on
     * {@code GITHUB_ACTIONS} does not help, because the tests run inside CI too, where the
     * variable is set: it made the annotations survive and a test assert on ambient state.
     * A test passes its own sink and prints nothing.
     */
    private static final Consumer<String> COMPLAIN = message -> System.err.println(
            System.getenv("GITHUB_ACTIONS") == null ? "sokar: " + message
                    : "::error::" + message);

    /**
     * Returns the API token, or refuses when there is none.
     * <p>
     * From the environment and never an argument: {@code /proc/<pid>/cmdline} is world readable,
     * and neither supported distribution mounts {@code /proc} with {@code hidepid}.
     * <p>
     * <strong>Taken as a parameter rather than read here.</strong> A test that called the version
     * which read the environment itself passed on a developer's machine, where nothing sets the
     * token, and on CI - where the workflow does set it - authenticated against the real project
     * and swept the server the build was running on. A function that reads ambient state cannot be
     * tested for what it does when that state is absent.
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
     * Returns what identifies this run.
     * <p>
     * The workflow run in CI; a timestamp locally, which is enough to tell two developers apart
     * and does not pretend to be more. Both legs of a matrix share the run, so the leg is what
     * makes a server's label unique - and a sweep deletes by that label.
     *
     * @param run The workflow run, or {@code null} outside CI.
     * @param leg Which leg of the matrix, or {@code null}.
     * @return The run id.
     */
    static String runId(String run, String leg) {
        if (run != null && !run.isBlank()) {
            return leg == null || leg.isBlank() ? run : run + "-" + leg;
        }
        return "local-" + System.currentTimeMillis();
    }
}
