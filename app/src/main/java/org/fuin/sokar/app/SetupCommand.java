package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.HookInstaller;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Installs and removes the OCI hook descriptors.
 * <p>
 * Kept out of the package's install scripts on purpose. Writing into an operator's podman
 * configuration is host integration, not file installation, and it should happen when the operator
 * asks for it rather than as a side effect of {@code apt install}.
 */
@Command(name = "setup",
        mixinStandardHelpOptions = true,
        description = "Installs the OCI hooks into this user's podman configuration.")
public class SetupCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--uninstall", description = "Removes the hook descriptors again.")
    private boolean uninstall;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final HookInstaller installer = context.hooks();

        if (uninstall) {
            out.println("removed   " + installer.uninstall() + " files from "
                    + context.paths().hooksDirectory());
            out.flush();
            return 0;
        }

        if (!installer.binariesPresent()) {
            err.println("sokar: the hook binaries are not in " + context.paths().binaryDirectory());
            err.flush();
            return 69;
        }

        for (final Path file : installer.install()) {
            out.println("installed " + file);
        }
        out.println();
        out.println("The hooks only fire for containers carrying Sokar's own annotation,");
        out.println("so other containers on this machine are unaffected.");
        out.flush();
        return 0;
    }
}
