package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.vault.SigningKey;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Puts this machine forward to take part in a project: its message key's public half, as a change to the
 * project's {@code allowed_signers}, waiting in the project's gate for a person's review.
 * <p>
 * <strong>No secret leaves the machine, and no key is copied by hand.</strong> The change is made from the
 * configuration this machine follows and verified, and put up for review as {@code enroll-<machine>}; a
 * person reads it - the review ranks a signer list first - and merges it signed with
 * {@code sokar gate approve enroll-<machine> --signed}. Every machine that follows the project then reads
 * the signer at its next reconciliation. {@code --remove} is the same path backwards.
 * <p>
 * <strong>The trust anchor stays a person's step:</strong> this prints the fingerprint of the key this
 * machine pinned for the project's configuration, beside this machine's own, for the comparison a person
 * makes; nothing here reads a key from the repository it verifies.
 */
@Command(name = "enroll",
        mixinStandardHelpOptions = true,
        description = "Puts this machine's message key up for review as a signer of a project.")
public class ProjectEnrollCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<project>", description = "A project this machine follows, verified.")
    private String projectName;

    @Option(names = "--remove", description = "Put this machine's removal up for review instead.")
    private boolean remove;

    @Option(names = "--as", paramLabel = "<principal>",
            description = "The name this machine signs messages as. Default: sokar@<host name>.")
    private @org.jspecify.annotations.Nullable String principal;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final FollowedProjects.Followed followed;
        try {
            followed = new FollowedProjects(context.paths().followed()).require(projectName);
        } catch (IOException | RuntimeException ex) {
            err.println("sokar: this machine does not follow '" + projectName + "'; enroll takes part in a project"
                    + " whose configuration it follows and verifies: 'sokar project follow' first");
            err.flush();
            return 64;
        }
        if (followed.unverified() || followed.commit().isBlank()) {
            err.println("sokar: '" + projectName + "' is followed " + (followed.unverified() ? "unverified"
                    : "without a configuration in force") + ", so nothing here says what the project's configuration"
                    + " is; follow it with --signed-by first");
            err.flush();
            return 64;
        }
        final Project project = GateSupport.byName(context, projectName);
        final String machine = principal != null ? principal : "sokar@" + hostName();
        final SigningKey key = HostKey.loadOrCreate(context.paths().messageKey(), machine);
        final String line = HostKey.allowedSignersLine(key, machine);
        final String ref = "enroll-" + machine.replaceAll("[^A-Za-z0-9.-]", "-");

        final org.fuin.sokar.gate.GitGate gate = GateSupport.gate(project, GateSupport.repository(project, null),
                null, null);
        gate.initialize();
        final Path mirror = GateSupport.mirror(project);
        final Path scratch = Files.createTempDirectory("sokar-enroll-");
        try {
            final String work = scratch.resolve("work").toString();
            git(null, "init", "--quiet", work);
            final Path noHooks = Files.createDirectories(scratch.resolve("work/.git/sokar-no-hooks"));
            git(work, "config", "core.hooksPath", noHooks.toString());
            // From the configuration in force, which is what every machine following the project has.
            git(work, "fetch", "--quiet", context.paths().followedClone(projectName).toString(), followed.commit());
            git(work, "checkout", "--quiet", "-B", ref, "FETCH_HEAD");
            final Path signers = scratch.resolve("work").resolve(KnownPeers.SIGNERS);
            final List<String> lines = new ArrayList<>(Files.isRegularFile(signers)
                    ? Files.readAllLines(signers, StandardCharsets.UTF_8) : List.of());
            final List<String> before = List.copyOf(lines);
            lines.removeIf(each -> each.startsWith(machine + " "));
            if (!remove) {
                lines.add(line);
            }
            if (lines.equals(before)) {
                out.println(remove ? "not enrolled " + machine + " is not a signer of " + projectName
                        : "enrolled  " + machine + " is a signer of " + projectName + " already");
                out.flush();
                return 0;
            }
            Files.write(signers, lines, StandardCharsets.UTF_8);
            git(work, "add", KnownPeers.SIGNERS);
            git(work, "commit", "--quiet", "-m", (remove ? "Remove " : "Enroll ") + machine + " as a signer of "
                    + projectName);
            // Replaces an earlier enrolment of this machine that nobody has decided about yet.
            git(work, "push", "--quiet", "--force", mirror.toString(), "HEAD:" + org.fuin.sokar.gate.GitGate.INCOMING + ref);
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(scratch)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }

        out.println((remove ? "removal   " : "enrolment ") + ref + " waits for a person in " + projectName + "'s gate");
        out.println("this machine " + machine + "  " + SignedBy.fingerprintOf(line.substring(line.indexOf(' ') + 1)));
        for (final String pinned : pinned(projectName)) {
            out.println("project   configuration signed by  " + pinned + "  (compare it with the one you were given)");
        }
        out.println("review    sokar gate review " + ref + " -p " + projectName);
        out.println("merge     sokar gate approve " + ref + " -p " + projectName + " --signed");
        out.flush();
        return 0;
    }

    /**
     * Returns the fingerprints this machine pinned for a project's configuration.
     *
     * @param name The project.
     * @return Their fingerprints.
     * @throws IOException If the pinned keys cannot be read.
     */
    private List<String> pinned(String name) throws IOException {
        final Path file = context.paths().configurationSigners();
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final List<String> fingerprints = new ArrayList<>();
        for (final String each : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final String[] fields = each.strip().split("\\s+");
            if (fields.length >= 3 && fields[0].equals(name)) {
                final String fingerprint = SignedBy.fingerprintOf(fields[1] + " " + fields[2]);
                if (fingerprint != null) {
                    fingerprints.add(fingerprint);
                }
            }
        }
        return fingerprints;
    }

    private CommandResult git(@org.jspecify.annotations.Nullable String directory, String... arguments) {
        final List<String> command = new ArrayList<>(List.of("git"));
        if (directory != null) {
            command.addAll(List.of("-C", directory));
        }
        command.addAll(List.of(arguments));
        final String machine = principal != null ? principal : "sokar@" + hostName();
        return context.runner().runOrFail(new org.fuin.sokar.core.process.Command(command, null, Map.of("GIT_AUTHOR_NAME", "Sokar",
                "GIT_AUTHOR_EMAIL", machine, "GIT_COMMITTER_NAME", "Sokar", "GIT_COMMITTER_EMAIL", machine,
                "GIT_TERMINAL_PROMPT", "0"), null));
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "localhost";
        }
    }
}
