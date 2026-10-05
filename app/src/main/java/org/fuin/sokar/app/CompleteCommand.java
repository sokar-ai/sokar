package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Answers what the word being typed could become. The other half of the completion scripts.
 * <p>
 * <strong>A callback rather than a generated list, because the names are alive.</strong>
 * {@code picocli-codegen} can generate a completion script, but it bakes the candidates into it as
 * literals - which is right for subcommand and option names and useless for container names, since
 * those change every time a task starts. So the script asks the program, the way kubectl and
 * docker both do, and this is what it asks.
 * <p>
 * <strong>The native image is what makes that affordable.</strong> A JVM round trip on every TAB
 * would be unusable; {@code sokar} starts in milliseconds, and what is left is the {@code podman
 * ps} behind it. Caching it would trade that for staleness, and offering a container that no
 * longer exists is worse than answering a tenth of a second late.
 * <p>
 * <strong>It never acts and never fails.</strong> Every answer is read from the command tree or
 * from a listing, and anything that goes wrong produces no candidates rather than an error - a
 * stack trace printed into the middle of somebody's command line would be worse than no
 * completion at all.
 */
@Command(name = "__complete",
        hidden = true,
        description = "Lists what the last word could be completed to.")
public class CompleteCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(arity = "0..*", paramLabel = "WORD",
            description = "The words typed so far, the last one being the partial word.")
    private List<String> words = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        try {
            candidatesFor(spec.commandLine().getParent(), words).forEach(out::println);
        } catch (RuntimeException ex) {
            // No candidates rather than a failure, see the class comment.
        }
        out.flush();
        // Always zero. A non-zero exit reaches the shell as a beep or an error in some setups,
        // and "there is nothing to complete" is an ordinary answer rather than a failure.
        return 0;
    }

    /**
     * Returns what the last word could become.
     *
     * @param root The command line to walk, or {@code null} when there is none.
     * @param typed The words typed so far, the first being the program name and the last the
     *            partial word being completed. An empty partial word is an ordinary case: it is
     *            what TAB on a fresh space means.
     * @return Candidates, sorted and free of duplicates, possibly empty.
     */
    List<String> candidatesFor(@Nullable CommandLine root, List<String> typed) {

        if (root == null || typed.isEmpty()) {
            return List.of();
        }

        final String partial = typed.getLast();
        // Everything but the program name and the partial word. Those in between say which
        // command we are in.
        final List<String> path = typed.subList(1, typed.size() - 1);

        CommandLine current = root;
        for (final String word : path) {
            final CommandLine next = current.getSubcommands().get(word);
            if (next != null) {
                current = next;
            }
            // A word that is not a subcommand is an option or an argument: it does not move us,
            // which is what makes 'task attach --help <TAB>' still complete inside 'attach'.
        }

        final Set<String> candidates = new LinkedHashSet<>();
        if (partial.startsWith("-")) {
            // Only the long forms. Offering '-p' beside '--project-file' doubles the list to say
            // the same thing twice, and the short one is what somebody types when they already
            // know it.
            for (final var option : current.getCommandSpec().options()) {
                if (!option.hidden()) {
                    for (final String name : option.names()) {
                        if (name.startsWith("--")) {
                            candidates.add(name);
                        }
                    }
                }
            }
        } else if (!current.getSubcommands().isEmpty()) {
            // The declared name, not the map key: picocli keys aliases into the same map, so
            // taking the keys would offer 'task' and 'tasks' as if they were two commands.
            current.getSubcommands().values().forEach(sub -> {
                if (!sub.getCommandSpec().usageMessage().hidden()) {
                    candidates.add(sub.getCommandName());
                }
            });
        } else if (current.getCommand() instanceof Suggests suggests) {
            // The same list the refusal offers. Two sources for "which names would this command
            // take" would answer differently exactly when it matters.
            candidates.addAll(suggests.candidates());
        }

        return candidates.stream().filter(candidate -> candidate.startsWith(partial)).sorted()
                .toList();
    }

    /**
     * Sets the words, for a test that drives this without a command line.
     *
     * @param typed The words.
     */
    void setWords(List<String> typed) {
        this.words = List.copyOf(typed);
    }
}
