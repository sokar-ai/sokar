package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.Duration;

/**
 * The command line, for the parts of this a workflow calls directly.
 * <p>
 * Only the sweep is here. Renting a machine is not a command, because a command that creates a
 * server and returns leaves the deleting to whoever remembers - which is the failure this whole
 * module exists to make structural. Callers that need a machine hold a {@link Rental} for as long
 * as they need it.
 */
public final class Main {

    /** How old a server must be before an unattended sweep counts it as left behind. */
    private static final Duration DEFAULT_AGE = Duration.ofHours(2);

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
            System.exit(run(args));
        } catch (IOException | IllegalStateException | IllegalArgumentException ex) {
            // The refusals this code makes on purpose - a missing token, an API that said no.
            // Anything else is a fault here and keeps its stack trace, because a one-line
            // message for a NullPointerException hides the only useful thing about it.
            System.err.println("::error::" + ex.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) throws IOException {
        if (args.length == 0 || !"sweep".equals(args[0])) {
            System.err.println("""
                Usage: sweep [--mine | --now | --older-than <hours>]

                  --mine               delete what this run created, and nothing else
                  --now                delete everything labelled sokar=ci, whatever its age
                  --older-than <hours> delete what is older than this (default 2)
                  (none)               list what would go, delete nothing

                The API token is read from the environment, never from an argument: everything on
                a command line is readable by every process on the machine.""");
            return 2;
        }

        boolean mine = false;
        boolean now = false;
        boolean dryRun = true;
        Duration olderThan = DEFAULT_AGE;
        for (int at = 1; at < args.length; at++) {
            switch (args[at]) {
                case "--mine" -> {
                    mine = true;
                    dryRun = false;
                }
                case "--now" -> {
                    now = true;
                    dryRun = false;
                }
                case "--older-than" -> {
                    if (at + 1 >= args.length) {
                        System.err.println("::error::--older-than needs a number of hours");
                        return 2;
                    }
                    olderThan = Duration.ofHours(Long.parseLong(args[++at]));
                    dryRun = false;
                }
                default -> {
                    System.err.println("::error::unknown option: " + args[at]);
                    return 2;
                }
            }
        }

        try (Hetzner hetzner = Hetzner.with(token(), runId())) {
            if (mine) {
                System.out.println("deleted " + hetzner.deleteMine() + " of this run's servers");
                return 0;
            }
            final int swept = hetzner.sweep(now ? Duration.ZERO : olderThan, dryRun);
            System.out.println((dryRun ? "would delete " : "deleted ") + swept + " server(s)");
            return 0;
        }
    }

    /**
     * Returns the API token.
     * <p>
     * From the environment and never an argument: {@code /proc/<pid>/cmdline} is world readable,
     * and neither supported distribution mounts {@code /proc} with {@code hidepid}.
     *
     * @return The token.
     */
    private static String token() {
        final String token = System.getenv("REMOTE_BUILD");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("No API token: set REMOTE_BUILD in the environment.");
        }
        return token;
    }

    /**
     * Returns what identifies this run.
     * <p>
     * The workflow run in CI; a timestamp locally, which is enough to tell two developers apart
     * and does not pretend to be more.
     *
     * @return The run id.
     */
    static String runId() {
        final String run = System.getenv("GITHUB_RUN_ID");
        final String leg = System.getenv("SOKAR_CI_LEG");
        if (run != null && !run.isBlank()) {
            return leg == null || leg.isBlank() ? run : run + "-" + leg;
        }
        return "local-" + System.currentTimeMillis();
    }
}
