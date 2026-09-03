package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.hardening.ProcessHardening;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Reports what the binary can see of its own environment.
 * <p>
 * Useful on its own, and it keeps the hardening downcall reachable from the shipped binary, so a
 * missing FFM registration would surface here rather than in the middle of starting a container.
 */
@Command(name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Reports paths and process hardening state.")
public class DoctorCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final XdgPaths paths = XdgPaths.current();
        out.println("config   " + paths.config());
        out.println("data     " + paths.data());
        out.println("state    " + paths.state());
        out.println("runtime  " + paths.runtime());

        out.println();
        out.println("dumpable            " + ProcessHardening.dumpable());
        out.println("no new privileges   " + ProcessHardening.noNewPrivileges());
        out.println("hardening covers    "
                + (ProcessHardening.appliesToWholeProcess() ? "the whole process" : "this thread only"));

        out.flush();

        // On a JVM this is a warning, in the shipped binary it must never appear.
        return ProcessHardening.appliesToWholeProcess() ? 0 : 1;
    }
}
