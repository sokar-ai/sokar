package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.hardening.ProcessHardening;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.shield.DnsmasqProbe;
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
public class DoctorCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Reports whether the installed dnsmasq can populate the firewall's allow set.
     * <p>
     * Without {@code --nftset} a declared domain resolves and is then dropped: names work, nothing
     * connects, and the cause is invisible. The failure is silent, so it is asked about here
     * rather than left to be discovered.
     *
     * @return A line describing the state.
     */
    private static String nftSetSupport() {
        try {
            final CommandResult result = new ProcessCommandRunner(java.time.Duration.ofSeconds(10))
                    .run(org.fuin.sokar.core.process.Command.of(DnsmasqProbe.versionCommand()));
            if (!result.successful()) {
                return "unknown - dnsmasq did not run (declared domains will not be reachable)";
            }
            return DnsmasqProbe.supportsNftSet(result.standardOutput())
                    ? "yes"
                    : "NO - this dnsmasq cannot open the firewall for declared domains";
        } catch (RuntimeException ex) {
            return "unknown - dnsmasq is not installed";
        }
    }

    /**
     * Returns the directories agents are scanned in, in order.
     *
     * @return Locations.
     */
    private java.util.List<java.nio.file.Path> agentLocations() {
        return context.paths().agentDirectory().locations();
    }

    /**
     * Reports binaries that are installed and never run, and says nothing when none are.
     *
     * @param out Where to write.
     */
    private void printShadowed(PrintWriter out) {

        final java.util.List<java.nio.file.Path> shadowed = new java.util.ArrayList<>();
        final java.nio.file.Path hooks = context.paths().shadowedHookBinaries();
        if (hooks != null) {
            shadowed.add(hooks);
        }
        shadowed.addAll(context.paths().agentDirectory().shadowed());
        if (shadowed.isEmpty()) {
            return;
        }
        out.println();
        for (int i = 0; i < shadowed.size(); i++) {
            out.println((i == 0 ? "not used " : "         ") + shadowed.get(i));
        }
        out.println("         installed, but a copy of your own is used instead");
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final XdgPaths paths = context.paths().xdg();
        out.println("config   " + paths.config());
        out.println("data     " + paths.data());
        out.println("state    " + paths.state());
        out.println("runtime  " + paths.runtime());

        out.println();
        out.println("hooks    " + context.paths().binaryDirectory());
        for (final java.nio.file.Path location : agentLocations()) {
            out.println("agents   " + location);
        }
        printShadowed(out);

        out.println();
        out.println("dnsmasq nftset      " + nftSetSupport());

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
