package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GateException;
import org.fuin.sokar.gate.GitGate;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Opens an agent's waiting work as a copy somebody can read in their own editor.
 * <p>
 * <strong>This exists so the safe way is also the convenient one.</strong> The unsafe path is two
 * ordinary git commands - fetch the branch out of the mirror into your own checkout, then push it
 * upstream - and it needs no privilege, so nothing prevents it. What can be changed is which path
 * is easier, and an accident of convenience is only ever fixed by convenience.
 * <p>
 * The copy has the mirror as its only remote, so there is nowhere to push except back to the gate;
 * no hook runs in it; and it is detached, so nothing in it looks like work somebody should carry
 * on. None of that is a warning somebody can click past.
 */
@Command(name = "checkout",
        mixinStandardHelpOptions = true,
        description = "Opens waiting work as a copy you can read. It can only go back to the gate.")
public class GateCheckoutCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "NAME",
            description = "Waiting ref, as 'sokar gate pending' lists it.")
    private String name;

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: project.yml in this directory.")
    private Path projectFile = Path.of("project.yml");

    @Option(names = "--into", paramLabel = "<dir>",
            description = "Where to write it. Default: a directory beside the project file.")
    private Path into;

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

        final Project project;
        try {
            project = GateSupport.project(projectFile);
        } catch (RuntimeException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        final Path target = into != null ? into
                : projectFile.toAbsolutePath().getParent().resolve("sokar-review-" + name);
        try {
            GateSupport.gate(project, null).checkout(name, target);
        } catch (GateException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        out.println("opened    " + target);
        // Said every time, because it is what makes this the safe path rather than a copy of the
        // unsafe one. Somebody who does not know it will treat this like any other checkout.
        out.println("remote    the gate's mirror, and nothing else - there is nowhere to push"
                + " that reaches the upstream");
        out.println("hooks     off in this copy, so nothing in the work runs by being opened");
        out.println("approve   'sokar gate approve " + name + " <branch>' is what sends it up");
        out.flush();
        return 0;
    }
}
