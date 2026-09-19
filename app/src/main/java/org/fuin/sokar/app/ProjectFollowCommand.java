package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Tells this account to take a project's configuration from a repository.
 * <p>
 * <strong>A person does this and nothing else does.</strong> No commit adds a project and nothing
 * discovers one: a configuration source that can enrol further configuration sources is a source
 * that grows where nobody is looking.
 */
@Command(name = "follow",
        mixinStandardHelpOptions = true,
        description = "Takes a project's configuration from its repository, from now on.")
public class ProjectFollowCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<name>", description = "The project's name.")
    private String name;

    @Parameters(index = "1", paramLabel = "<url>",
            description = "Where its repository is. Fetched with this account's own credentials.")
    private String url;

    @picocli.CommandLine.Option(names = "--accept-rewrite",
            description = "Takes a signed commit that is not a descendant of the one in force."
                    + " Say this only when you know why the history moved.")
    private boolean acceptRewrite;

    @Option(names = "--unverified",
            description = "Follows without an anchor: what the repository says is applied without"
                    + " any signature being checked. Whoever may push there then decides what"
                    + " tasks here may reach. Reported as a state wherever this project is shown.")
    private boolean unverified;

    @Option(names = "--signed-by", paramLabel = "<key>",
            description = "The public key this project's configuration is signed with, as"
                    + " 'ssh-ed25519 AAAA...'. Pins it for this project and follows in one"
                    + " command. NEVER read from the repository it verifies.")
    private String signedBy;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (unverified && signedBy != null) {
            // Both is not a stricter setting, it is two different instructions. Refused rather
            // than one of them silently winning.
            err.println("sokar: --unverified and --signed-by say opposite things. Pick one.");
            err.flush();
            return 64;
        }
        if (signedBy != null) {
            try {
                // The key comes from the PERSON, in the same command - which is a different
                // channel from the repository, and that is the whole of what an anchor is.
                pin(name, signedBy);
            } catch (final java.io.IOException ex) {
                err.println("sokar: cannot pin the key: " + ex.getMessage());
                err.flush();
                return 70;
            }
        }
        final FollowedProjects projects = new FollowedProjects(context.paths().followed());
        try {
            projects.follow(name, url, unverified);
        } catch (final IllegalArgumentException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
        if (acceptRewrite) {
            // Forgetting what is in force is the whole of accepting: the next reconcile then has
            // nothing to descend from and applies what it verifies.
            final FollowedProjects.Followed known = projects.find(name);
            projects.write(new FollowedProjects.Followed(known.name(), known.url(), "",
                    known.at(), known.outcome(), known.detail()));
        }
        // Once, now, rather than at the next tick: somebody who typed this wants to know whether it
        // works, and a refusal an hour later is a refusal nobody connects to what they did.
        final Reconcile.Result result = new Reconcile(context).run(projects.find(name));
        projects.write(Reconcile.after(projects.find(name), result));

        out.println("following  " + name + "  " + url
                + (unverified ? "  (unverified - whoever can push there decides what tasks here"
                        + " may reach)" : ""));
        switch (result.outcome()) {
            case APPLIED, UNCHANGED -> out.println("applied    " + result.commit());
            case REWRITTEN -> {
                err.println("refused    " + result.detail());
                err.flush();
                out.flush();
                return 70;
            }
            case NOT_SIGNED, UNKNOWN_KEY, NO_ANCHOR, UNREADABLE -> {
                err.println("refused    " + result.detail());
                if (!result.refused().isEmpty()) {
                    // Which commit was turned away, beside what is running. Two questions.
                    err.println("           refused    " + result.refused());
                }
                err.println("           the project is followed; nothing of it is in force");
                err.flush();
                out.flush();
                return 70;
            }
            default -> {
                err.println(result.outcome().name().toLowerCase(java.util.Locale.ROOT)
                        + "    " + result.detail());
                err.flush();
                out.flush();
                return 70;
            }
        }
        out.flush();
        return 0;
    }

    /**
     * Writes one key into this machine's pinned signers.
     * <p>
     * Appended rather than replacing the file: a machine follows several projects and they need
     * not share a key. A key already there is not written twice.
     *
     * @param principal What to call it, which is a label rather than a check.
     * @param key The public key, as ssh-keygen prints it.
     * @throws java.io.IOException Writing failed.
     */
    private void pin(final String principal, final String key) throws java.io.IOException {
        final java.nio.file.Path signers = context.paths().configurationSigners();
        java.nio.file.Files.createDirectories(signers.getParent());
        final String[] fields = key.strip().split("\\s+");
        if (fields.length < 2) {
            throw new java.io.IOException("'" + key + "' is not a public key: expected"
                    + " 'ssh-ed25519 AAAA...'");
        }
        final String line = principal + " " + fields[0] + " " + fields[1];
        final java.util.List<String> lines = java.nio.file.Files.exists(signers)
                ? new java.util.ArrayList<>(java.nio.file.Files.readAllLines(signers))
                : new java.util.ArrayList<>();
        if (!lines.contains(line)) {
            lines.add(line);
        }
        java.nio.file.Files.writeString(signers, String.join("\n", lines) + "\n");
    }
}
