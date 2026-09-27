package org.fuin.sokar.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.agent.api.InstalledAgents;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Answers whether a push carries work an agent wrote and nobody approved.
 * <p>
 * <strong>Meant to be run by a {@code pre-push} hook, and useful on its own.</strong> Reads what
 * git gives a hook on standard input - {@code <local ref> <local sha> <remote ref> <remote sha>}
 * per line - works out which commits are about to leave, and names any whose author is an agent
 * installed here.
 * <p>
 * <strong>Author identity, and the reason is measured rather than assumed.</strong> It was once held
 * that an author is "easy to lose in a rebase" and that a trailer added at approval time would be
 * safer. Measured: {@code git rebase} <em>preserves</em> the author and changes only the
 * committer, through an ordinary rebase, an interactive one and an {@code --amend}. So the cheap
 * signal is also the durable one, and nothing has to rewrite commits when work is approved.
 * <p>
 * It is spoofable, and that does not matter: this is protection against an accident, not against
 * the owner of the machine. Somebody who rewrites an author to get past it is exactly the person
 * the requirement says may still do it.
 */
@picocli.CommandLine.Command(name = "check",
        mixinStandardHelpOptions = true,
        description = "Reads a pre-push hook's input and refuses agent work nobody approved.")
public class GateCheckCommand implements Callable<Integer>, SokarFactory.ContextAware {

    /** git's own way of saying "there is nothing on the far side yet". */
    static final String ABSENT = "0000000000000000000000000000000000000000";

    /**
     * One commit that is leaving and should not be.
     *
     * @param commit Its abbreviated id.
     * @param author The author's address, as recorded.
     * @param subject Its first line.
     */
    record Offender(String commit, String author, String subject) { }

    @Option(names = "--quiet",
            description = "Says nothing when there is nothing to say.")
    private boolean quiet;

    // git passes the remote's name and URL to a pre-push hook as arguments. The name is what
    // makes "new to the far side" answerable: without it the check falls back to every remote.
    @picocli.CommandLine.Parameters(index = "0..*", arity = "0..2", hidden = true,
            paramLabel = "<remote> <url>",
            description = "What git hands the hook: the remote's name and its URL.")
    private List<String> remote = List.of();

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    private static Command git(@Nullable Path directory, List<String> arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(arguments);
        return new Command(all, directory, Map.of(), null);
    }

    /**
     * Returns the commits a push would send, for one line of a hook's input.
     *
     * @param runner Runs git.
     * @param directory The repository being pushed from, or {@code null} for this one.
     * @param localSha What is being pushed.
     * @param remoteSha What the far side has, or forty zeros.
     * @param remote The remote's name as git named it, or {@code null} when it is not known.
     * @return Commit ids, newest first.
     */
    static List<String> commits(CommandRunner runner, @Nullable Path directory,
            String localSha, String remoteSha, @Nullable String remote) {
        if (ABSENT.equals(localSha)) {
            // A deletion. Nothing is being sent, so there is nothing to look at.
            return List.of();
        }
        final List<String> arguments = new ArrayList<>(List.of("rev-list"));
        if (ABSENT.equals(remoteSha)) {
            // A branch the far side does not have, so there is no base to subtract. The first
            // form written here was "--not --all", and measured it answers zero commits every
            // time: the ref being pushed is itself one of --all, so it cancels itself out. That
            // is the worst kind of wrong - it installs, it runs, it never fires. What is new to
            // the far side is what no tracking ref of that remote already has, and where there
            // are no tracking refs that is the whole branch, which is both conservative and
            // literally true.
            arguments.add(localSha);
            arguments.add("--not");
            arguments.add(remote == null || remote.isBlank() ? "--remotes" : "--remotes=" + remote);
        } else {
            arguments.add(remoteSha + ".." + localSha);
        }
        final var result = runner.run(git(directory, arguments));
        return result.successful()
                ? result.standardOutput().lines().map(String::strip)
                        .filter(line -> !line.isEmpty()).toList()
                : List.of();
    }

    /**
     * Returns the commits in a push that an agent wrote.
     * <p>
     * The whole decision, separated from where the input came from and where the answer goes, so
     * a test can put a real repository and a real push in front of it.
     *
     * @param runner Runs git.
     * @param directory The repository being pushed from, or {@code null} for this one.
     * @param hookInput The lines git hands a pre-push hook.
     * @param agentEmails Addresses the installed agents commit under, lower case.
     * @param remote The remote's name as git named it, or {@code null} when it is not known.
     * @return What an agent wrote, in the order the push would send it.
     */
    static List<Offender> offenders(CommandRunner runner, @Nullable Path directory,
            List<String> hookInput, Set<String> agentEmails, @Nullable String remote) {
        final List<Offender> found = new ArrayList<>();
        if (agentEmails.isEmpty()) {
            return found;
        }
        final Set<String> seen = new LinkedHashSet<>();
        for (final String line : hookInput) {
            final String[] fields = line.strip().split("\\s+");
            if (fields.length < 4) {
                // Not a hook line. Anything else on that stream is somebody running this by hand.
                continue;
            }
            for (final String commit : commits(runner, directory, fields[1], fields[3],
                    remote)) {
                if (!seen.add(commit)) {
                    // Two refs pushed together share history. Naming a commit twice would make
                    // "3 commits" mean something no count of the repository could confirm.
                    continue;
                }
                final var shown = runner.run(git(directory,
                        List.of("show", "--no-patch", "--format=%ae|%s", commit)));
                if (!shown.successful()) {
                    continue;
                }
                final String[] parts = shown.standardOutput().strip().split("\\|", 2);
                if (agentEmails.contains(parts[0].toLowerCase(Locale.ROOT))) {
                    found.add(new Offender(commit.substring(0, Math.min(9, commit.length())),
                            parts[0], parts.length > 1 ? parts[1] : ""));
                }
            }
        }
        return found;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final Set<String> agentEmails = new LinkedHashSet<>();
        try (InstalledAgents agents = context.agents()) {
            for (final InstalledAgent agent : agents.all()) {
                agentEmails.add(agent.definition().gitIdentity().email().toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException ex) {
            // A machine whose agents cannot be asked cannot answer this question. Saying so beats
            // letting a push through silently, which would be a guard that is absent without
            // anybody noticing - the failure this whole feature is about.
            err.println("sokar: cannot ask which agents are installed, so this push was not"
                    + " checked: " + ex.getMessage());
            err.flush();
            return 0;
        }
        if (agentEmails.isEmpty()) {
            return 0;
        }

        final List<String> hookInput = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                hookInput.add(line);
            }
        } catch (IOException ex) {
            err.println("sokar: could not read what is being pushed: " + ex.getMessage());
            err.flush();
            return 0;
        }

        final List<Offender> found = offenders(context.runner(), null, hookInput, agentEmails,
                remote.isEmpty() ? null : remote.get(0));
        if (found.isEmpty()) {
            if (!quiet) {
                out.println("nothing an agent wrote is in this push");
                out.flush();
            }
            return 0;
        }

        err.println("sokar: this push carries " + found.size()
                + " commit(s) an agent wrote, which nobody approved at the gate:");
        found.forEach(offender -> err.println("  " + offender.commit() + "  "
                + offender.subject() + "  (" + offender.author() + ")"));
        err.println();
        err.println("  The gate exists so somebody reads this before it leaves:");
        err.println("    sokar gate pending                 what is waiting");
        err.println("    sokar gate checkout <name>         open it where it cannot escape");
        err.println("    sokar gate approve <name> <branch> send it up");
        err.println();
        err.println("  If you meant this, push again with --no-verify.");
        err.flush();
        return 1;
    }
}
