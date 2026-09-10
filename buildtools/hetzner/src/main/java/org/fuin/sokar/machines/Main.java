package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.Duration;
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

    /**
     * Runs a sweep.
     *
     * @param args {@code sweep} with {@code --mine}, {@code --now}, {@code --older-than <hours>}
     *     or nothing, which lists what would go.
     */
    public static void main(String[] args) {
        try {
            System.exit(run(args, () -> Hetzner.with(
                    token(System.getenv("REMOTE_BUILD")), runId(System.getenv("GITHUB_RUN_ID"),
                            System.getenv("SOKAR_CI_LEG")))));
        } catch (IOException | IllegalStateException | IllegalArgumentException ex) {
            // The refusals this code makes on purpose - a missing token, an API that said no.
            // Anything else is a fault here and keeps its stack trace, because a one-line
            // message for a NullPointerException hides the only useful thing about it.
            System.err.println("::error::" + ex.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args, Supplier<Hetzner> open) throws IOException {
        if (args.length > 0 && "snapshot".equals(args[0])) {
            return snapshot(args, open);
        }
        if (args.length == 0 || !"sweep".equals(args[0])) {
            System.err.println("""
                Usage: sweep [--mine] [--now] [--older-than <minutes>]
                       snapshot --os <ubuntu|fedora> [--key <file>] [--repo <dir>]

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
        boolean delete = false;
        Duration olderThan = DEFAULT_AGE;
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--mine" -> mine = true;
                case "--now" -> delete = true;
                case "--older-than" -> {
                    if (at + 1 >= args.length) {
                        System.err.println("::error::--older-than needs a number of minutes");
                        return 2;
                    }
                    try {
                        olderThan = Duration.ofMinutes(Long.parseLong(args[++at]));
                    } catch (NumberFormatException ex) {
                        System.err.println("::error::--older-than needs a number of minutes, not '"
                                + args[at] + "'");
                        return 2;
                    }
                }
                default -> {
                    System.err.println("::error::unknown option: " + args[at]);
                    return 2;
                }
            }
        }

        try (Hetzner hetzner = open.get()) {
            if (mine) {
                // Always deletes, whatever else was passed: a job cleaning up after itself is
                // not a question, and age does not come into it.
                System.out.println("deleted " + hetzner.deleteMine() + " of this run's servers");
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
    private static int snapshot(String[] args, Supplier<Hetzner> open) throws IOException {
        String os = null;
        String key = null;
        String repo = null;
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--os" -> os = at + 1 < args.length ? args[++at] : null;
                case "--key" -> key = at + 1 < args.length ? args[++at] : null;
                case "--repo" -> repo = at + 1 < args.length ? args[++at] : null;
                default -> {
                    System.err.println("::error::unknown option: " + args[at]);
                    return 2;
                }
            }
        }
        if (os == null) {
            System.err.println("::error::snapshot needs --os");
            return 2;
        }
        final Credential credential = Credential.of(System.getenv("SSH"),
                key == null ? null : java.nio.file.Path.of(key));
        java.nio.file.Path archive = null;
        String musl = null;
        if (repo != null) {
            final java.nio.file.Path root = java.nio.file.Path.of(repo);
            archive = java.nio.file.Files.createTempFile("sokar-tree", ".tar");
            // What is checked out, not what is committed: a snapshot built from a working tree
            // has to be built from that tree.
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
            musl = java.nio.file.Files.readString(root.resolve("buildtools/install-musl.sh"));
        }
        try (Hetzner hetzner = open.get()) {
            Snapshots.build(hetzner, os, Snapshots.BUILD_TYPES, credential, archive, musl);
        }
        return 0;
    }

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
            throw new IllegalStateException("No API token: set REMOTE_BUILD in the environment.");
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
