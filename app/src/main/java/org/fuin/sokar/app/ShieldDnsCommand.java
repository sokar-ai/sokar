package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.shield.DnsPolicy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Runs the container's own DNS resolver.
 * <p>
 * Without it, the agent uses whatever resolver the host uses and the firewall sees only addresses,
 * so a name the operator would have recognised arrives at the Allow prompt as an IP nobody can
 * place. With it, names resolve only where the project allows them.
 */
@Command(name = "dns",
        mixinStandardHelpOptions = true,
        description = "Runs the container's DNS resolver, answering only for allowed domains.")
public class ShieldDnsCommand implements Callable<Integer> {

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: ${DEFAULT-VALUE}")
    private Path projectFile = Path.of("project.yml");

    @Option(names = "--pid", paramLabel = "<n>", required = true,
            description = "Host process id of the container's init process.")
    private long containerPid;

    @Option(names = "--allow", paramLabel = "<domain>", split = ",",
            description = "Domains that may resolve. Everything else is NXDOMAIN.")
    private List<String> allowed = List.of();

    @Option(names = "--upstream", paramLabel = "<ip>", split = ",",
            description = "Resolvers to forward allowed queries to. Default: ${DEFAULT-VALUE}")
    private List<String> upstream = List.of("8.8.8.8");

    @Option(names = "--config-only", paramLabel = "<file>",
            description = "Writes the configuration and exits, without starting dnsmasq.")
    private Path configOnly;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws IOException, InterruptedException {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final Project project = ProjectReader.read(projectFile);

        final DnsPolicy policy = new DnsPolicy(project.securityClass());
        allowed.forEach(policy::allow);
        upstream.forEach(policy::upstream);

        final Path config = configOnly != null ? configOnly
                : Files.createTempFile("sokar-dns-", ".conf");
        Files.writeString(config, policy.render(), StandardCharsets.UTF_8);
        out.println("config    " + config);
        out.flush();

        if (configOnly != null) {
            return 0;
        }

        final Process dnsmasq = new ProcessBuilder(
                DnsPolicy.command(containerPid, config.toString())).inheritIO().start();
        out.println("resolver  " + DnsPolicy.LISTEN_ADDRESS + ":" + DnsPolicy.PORT
                + " in the namespace of pid " + containerPid);
        out.flush();

        final int code = dnsmasq.waitFor();
        if (code != 0) {
            err.println("sokar: dnsmasq exited with " + code);
            err.flush();
        }
        return code == 0 ? 0 : 70;
    }
}
