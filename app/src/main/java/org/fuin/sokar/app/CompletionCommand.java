package org.fuin.sokar.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Prints the shell script that turns TAB into a question for {@code sokar __complete}.
 * <p>
 * <strong>The package installs the same two files</strong>, so on a machine where Sokar was
 * installed from the deb or the rpm this command is not needed at all. It exists for the cases the
 * package cannot reach: a binary somebody copied, a shell whose completion directory is not the
 * system one, and reading what a shell is about to be told to do before telling it.
 * <p>
 * The scripts are resources rather than generated text. Generating them would put shell quoting
 * inside Java string literals, where nobody can run it to see whether it works.
 */
@Command(name = "completion",
        mixinStandardHelpOptions = true,
        description = "Prints the TAB completion script for a shell.")
public class CompletionCommand implements Callable<Integer>, Suggests {

    /** The shells with a script, and the resource holding each. */
    private static final java.util.Map<String, String> SCRIPTS =
            java.util.Map.of("bash", "/completion/sokar", "zsh", "/completion/_sokar");

    @Parameters(index = "0", arity = "0..1", paramLabel = "SHELL",
            description = "Which shell: bash or zsh.")
    private String shell;

    @Spec
    private CommandSpec spec;

    @Override
    public List<String> candidates() {
        return SCRIPTS.keySet().stream().sorted().toList();
    }

    @Override
    public String candidateLabel() {
        return "shells with a completion script";
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // Asked without a shell. The usual refusal rather than a default: guessing from $SHELL
        // would write a bash script into a zsh startup file for anybody whose login shell is not
        // the one they are setting up.
        final String name = shell == null ? null : shell.toLowerCase(Locale.ROOT);
        final String resource = name == null ? null : SCRIPTS.get(name);
        if (resource == null) {
            err.println("sokar: " + (name == null ? "which shell?"
                    : "no completion script for '" + shell + "'"));
            Suggests.offer(err, this);
            err.flush();
            return 64;
        }

        try (InputStream script = CompletionCommand.class.getResourceAsStream(resource)) {
            if (script == null) {
                // Only reachable from a build that lost the resource, and it must not look like
                // an empty script - a shell told to source nothing completes nothing, silently.
                err.println("sokar: this build carries no " + name + " completion script");
                err.flush();
                return 70;
            }
            out.print(new String(script.readAllBytes(), StandardCharsets.UTF_8));
            out.flush();
            return 0;
        } catch (IOException ex) {
            err.println("sokar: the " + name + " completion script could not be read: "
                    + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
