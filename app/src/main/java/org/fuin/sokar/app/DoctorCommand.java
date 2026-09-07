package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.hardening.ProcessHardening;
import org.fuin.sokar.wire.SocketContext;
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
     * Reports whether podman will actually run Sokar's hooks.
     * <p>
     * The hooks are what load the firewall. Without them a container starts with no egress policy
     * at all and looks entirely normal, so this is the one line here that decides the exit code.
     *
     * @return A line describing the state.
     */
    private String hookRegistration() {
        return switch (context.hooks().registration()) {
            case ACTIVE -> "registered";
            case MISSING -> "NOT REGISTERED - a task would run with no firewall; run 'sokar setup'";
            case DANGLING -> "BROKEN - the descriptors name hook binaries that are not installed;"
                    + " run 'sokar setup' again";
            case SHADOWED -> "IGNORED - another containers.conf.d drop-in sorts after Sokar's and"
                    + " points hooks_dir at " + context.hooks().effectiveHooksDirectories();
        };
    }

    /**
     * Reports whether a task container will be able to reach Sokar's sockets.
     * <p>
     * Without the policy the container's connection is refused and the agent reports an
     * authentication failure, so the cause is asked about here rather than left to be guessed.
     *
     * @return A line describing the state.
     */
    private String socketPolicy() {
        if (!SocketContext.selinuxPresent()) {
            return "not needed - this machine does not run SELinux";
        }
        if (SocketContext.available()) {
            return "installed";
        }
        return "MISSING - a task cannot reach the vault proxy; install it with "
                + context.paths().selinuxInstaller();
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

    /**
     * Reports the container runtime's version, and whether Sokar will run on it.
     * <p>
     * podman 4 is refused: it has no pasta, so the host's loopback cannot be mapped into a
     * container and the git gate would bind every interface. Reported here as well as at task
     * start, because this is the command an operator runs to find out what their machine can do.
     *
     * @return The version, with what is wrong with it when something is.
     */
    private String podmanVersion() {
        final java.util.Optional<String> tooOld = context.podman().unsupportedVersion();
        return tooOld.isPresent() ? "NO - " + tooOld.get() : context.podman().version();
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
        out.println("podman              " + podmanVersion());
        out.println("hooks registered    " + hookRegistration());
        out.println("dnsmasq nftset      " + nftSetSupport());
        out.println("selinux policy      " + socketPolicy());

        out.println();
        out.println("dumpable            " + ProcessHardening.dumpable());
        out.println("no new privileges   " + ProcessHardening.noNewPrivileges());
        out.println("hardening covers    "
                + (ProcessHardening.appliesToWholeProcess() ? "the whole process" : "this thread only"));

        out.flush();

        // Hooks that will not run are the one thing here that makes a machine unsafe rather than
        // merely odd: a container starts without its firewall and nothing else says so. A podman
        // too old to support is the other: no task will start at all.
        if (context.hooks().registration()
                != org.fuin.sokar.runtime.HookInstaller.Registration.ACTIVE
                || context.podman().unsupportedVersion().isPresent()) {
            return 69;
        }

        // On a JVM this is a warning, in the shipped binary it must never appear.
        return ProcessHardening.appliesToWholeProcess() ? 0 : 1;
    }
}
