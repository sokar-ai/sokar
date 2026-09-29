package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Refuses a workflow step fetched by a name its owner may repoint.
 * <p>
 * <strong>Every step a workflow does not own runs beside the credentials the job holds.</strong> A tag
 * such as {@code @v7} is a promise to run whatever that name refers to when the job starts: a new
 * release, a retargeted tag, or whoever took the account over. A commit names one tree. So every
 * {@code uses:} names a commit, forty hexadecimal digits, and carries the version it stands for as a
 * comment - a pin nobody can read is a pin nobody updates. Only a step in the repository itself
 * ({@code ./...}) is exempt, because its history is the repository's own; a container image is held
 * to its digest.
 * <p>
 * GitHub's own actions are held to the same rule: the argument is the same, and the credentials stand
 * next to them all the same.
 */
final class CheckActions {

    /** {@code uses:} as a key, whether or not it opens a list item, and what follows it. */
    private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s+)?uses:\\s*(\\S+)(.*)$");

    /** A third-party step pinned to a commit. */
    private static final Pattern BY_COMMIT = Pattern.compile("^[\\w.-]+/[\\w./-]+@[0-9a-f]{40}$");

    /** A container image pinned to its digest. */
    private static final Pattern BY_DIGEST = Pattern.compile("^docker://[^@\\s]+@sha256:[0-9a-f]{64}$");

    /** The readable version beside a pin. */
    private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*\\S+");

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     */
    CheckActions(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Checks every workflow and action under a directory.
     *
     * @param directory usually the repository's {@code .github}
     * @return 0 when every step is pinned, {@link Stop#REFUSED} when one is not, {@link Stop#UNANSWERED}
     *         when the files cannot be read
     */
    int check(Path directory) {
        if (!Files.isDirectory(directory)) {
            err.println("there is no " + directory + " to check - run this from the repository's root, or name"
                    + " the directory");
            return Stop.UNANSWERED;
        }
        final List<String> faults = new ArrayList<>();
        int steps = 0;
        try (Stream<Path> files = Files.walk(directory)) {
            for (final Path file : files.filter(CheckActions::isYaml).sorted().toList()) {
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int at = 0; at < lines.size(); at++) {
                    final Matcher uses = USES.matcher(lines.get(at));
                    if (!uses.matches()) {
                        continue;
                    }
                    steps++;
                    final String fault = fault(unquoted(uses.group(1)), uses.group(2));
                    if (fault != null) {
                        faults.add(directory.relativize(file) + ":" + (at + 1) + ": " + fault);
                    }
                }
            }
        } catch (IOException ex) {
            err.println("could not read the workflows under " + directory + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        if (!faults.isEmpty()) {
            faults.forEach(err::println);
            err.println(faults.size() + " step(s) fetched by a name their owner may repoint. Pin each to the"
                    + " commit its version stands for, with the version beside it:");
            err.println("    uses: owner/action@<40-digit commit> # v1.2.3");
            err.println("The commit is the one the release tag points at: gh api"
                    + " repos/<owner>/<action>/commits/v1.2.3 --jq .sha. Dependabot keeps the pins current.");
            return Stop.REFUSED;
        }
        out.println("OK    " + steps + " step(s) under " + directory + ", each pinned to a commit or its own");
        return 0;
    }

    /**
     * Says what is wrong with one step, or nothing.
     *
     * @param reference what {@code uses:} names
     * @param rest what follows it on the line
     * @return the fault, or {@code null}
     */
    static @org.jspecify.annotations.Nullable String fault(String reference, String rest) {
        if (reference.startsWith("./")) {
            return null;
        }
        if (reference.startsWith("docker://")) {
            return BY_DIGEST.matcher(reference).matches() ? null
                    : reference + " names an image by a tag; pin it to its sha256 digest";
        }
        if (!BY_COMMIT.matcher(reference).matches()) {
            return reference + " names a tag or a branch, not a commit";
        }
        if (!VERSION.matcher(rest).find()) {
            return reference + " carries no version beside it; add it as a comment, '# v1.2.3'";
        }
        return null;
    }

    private static String unquoted(String value) {
        if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'")
                || value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean isYaml(Path file) {
        final String name = file.getFileName().toString();
        return Files.isRegularFile(file) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }
}
