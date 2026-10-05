package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GateException;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.gate.ReviewRanking;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Shows what a pending push would change.
 */
@Command(name = "review",
        mixinStandardHelpOptions = true,
        description = "Shows what a pending push would change.")
public class GateReviewCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = { "-p", "--project" }, paramLabel = "<name>", required = true,
            description = "Project name, as 'sokar project list' prints it.")
    private String projectName;

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories. Default: the one the task worked on.")
    private @Nullable String repository;

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream repository to forward approved pushes to.")
    private @Nullable String upstream;

    @Parameters(index = "0", paramLabel = "<name>", description = "Name of the pending push.")
    private String name;

    @Option(names = "--against", paramLabel = "<ref>",
            description = "Ref to compare against. Default: the mirror's main branch.")
    private String against = "main";

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
        final PrintWriter err = spec.commandLine().getErr();

        try {
            final Project project = GateSupport.byName(context, projectName);
            final GitGate gate = GateSupport.gate(context, project,
                    GateSupport.repository(context, project, repository, name), upstream, null);
            gate.initialize();
            // What matters before what is merely large: the instruction, the files dangerous by kind,
            // and the patch in that same order, reformatting and generated files last.
            final ReviewRanking.Review review = gate.rankedReview(name, against == null ? "HEAD" : against);
            out.print(ReviewText.render(ReviewText.instruction(context.paths(), project.name(), name),
                    review.files()));
            out.println(shown(gate.log(name, against)));
            out.println(shown(review.patch()));
            out.flush();
            return 0;
        } catch (GateException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        } catch (RuntimeException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }
    }

    /**
     * Returns the agent's text with every control character written out but the line breaks and tabs a patch needs.
     * <p>
     * Printed raw, a subject or a patch line carrying {@code ESC[1A ESC[2K} erased the lines before it - the warning to
     * read first among them - and the person approved what they had not seen.
     *
     * @param text What git printed of the agent's work.
     * @return The same, safe to print.
     */
    static String shown(final String text) {
        final StringBuilder safe = new StringBuilder(text.length());
        text.lines().forEach(line -> safe.append(TalkReadCommand.shown(line)).append('\n'));
        return text.endsWith("\n") || safe.isEmpty() ? safe.toString() : safe.substring(0, safe.length() - 1);
    }
}
