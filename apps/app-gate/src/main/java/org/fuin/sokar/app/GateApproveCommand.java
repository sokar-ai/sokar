package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GateException;
import org.fuin.sokar.gate.GitGate;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Forwards a reviewed push to the upstream.
 */
@Command(name = "approve",
        mixinStandardHelpOptions = true,
        description = "Forwards a reviewed push to the upstream.")
public class GateApproveCommand implements Callable<Integer>, SokarFactory.ContextAware {

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

    @Option(names = "--branch", paramLabel = "<name>",
            description = "Upstream branch. Default: ${DEFAULT-VALUE}")
    private String branch = "main";

    @Option(names = "--commit", paramLabel = "<id>",
            description = "The commit you reviewed, as 'gate pending' or 'gate review' shows it. Refused when the push"
                    + " has moved since, so what goes is what you read.")
    private @org.jspecify.annotations.Nullable String commit;

    @Option(names = "--signed",
            description = "Merge the work onto the branch as a commit signed with your own git's signing key, and"
                    + " push that - for what the project's configuration is made of, which every machine accepts"
                    + " only signed.")
    private boolean signed;

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
            if (signed) {
                GateSupport.approveSigned(gate, name, branch, commit);
                out.println("merged    " + name + " onto " + branch + ", signed, and forwarded");
            } else {
                gate.approve(name, branch, commit);
                out.println("forwarded " + name + " to " + branch);
            }
            out.flush();
            return 0;
        } catch (GateException.BranchExists ex) {
            // The words the contract names, and the next free branch to approve onto: never a force over this one.
            err.println("sokar: " + ex.branch() + " already holds " + ex.at() + ", from earlier work; approve onto"
                    + " another branch with --branch " + suggested(ex.branch()));
            err.flush();
            return 70;
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
     * Returns the next free branch after one that holds earlier work: {@code <branch>-2}, then {@code -3}, as the
     * upstream lists them, or {@code -2} when it cannot be asked.
     *
     * @param taken The branch that holds earlier work.
     * @return A suggestion only; the person chooses.
     */
    private String suggested(final String taken) {
        try {
            final Project project = GateSupport.byName(context, projectName);
            final GitGate gate = GateSupport.gate(context, project,
                    GateSupport.repository(context, project, repository, name), upstream, null);
            return gate.nextFreeBranch(taken);
        } catch (RuntimeException ex) {
            return taken + "-2";
        }
    }
}
