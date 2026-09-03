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
public class DoctorCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

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

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final XdgPaths paths = XdgPaths.current();
        out.println("config   " + paths.config());
        out.println("data     " + paths.data());
        out.println("state    " + paths.state());
        out.println("runtime  " + paths.runtime());

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
