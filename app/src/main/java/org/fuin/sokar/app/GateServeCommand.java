package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GateException;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.gate.GitHttpServer;
import org.fuin.sokar.gate.GitSubprocess;
import org.fuin.sokar.gate.TaskToken;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Serves the project's mirror over git smart-HTTP for one task.
 * <p>
 * The agent is given the printed URL and token, and nothing else. It can push, and it cannot reach
 * the upstream: the token authenticates only to this endpoint, only for this task, and the mirror
 * forwards nothing without an explicit approval.
 */
@Command(name = "serve",
        mixinStandardHelpOptions = true,
        description = "Serves the project mirror for an agent to push to.")
public class GateServeCommand implements Callable<Integer> {

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: ${DEFAULT-VALUE}")
    private Path projectFile = Path.of("project.yml");

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream repository, used only when approving.")
    private String upstream;

    @Option(names = "--address", paramLabel = "<ip>",
            description = "Address to bind. Default: ${DEFAULT-VALUE}")
    private String address = "127.0.0.1";

    @Option(names = "--port", paramLabel = "<n>",
            description = "Port to bind, or 0 to pick one. Default: ${DEFAULT-VALUE}")
    private int port;

    @Option(names = "--pid-file", paramLabel = "<file>",
            description = "Writes this process's id here, so the poststop hook can reap it.")
    private Path pidFile;

    @Option(names = "--seconds", paramLabel = "<n>",
            description = "Stop after this long. Zero means run until killed.")
    private int seconds;

    @Spec
    private CommandSpec spec;

    private void writePidFile(PrintWriter err) {
        if (pidFile == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(pidFile, String.valueOf(ProcessHandle.current().pid()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            err.println("sokar: cannot write " + pidFile + ": " + ex.getMessage());
            err.flush();
        }
    }

    @Override
    public Integer call() throws Exception {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {

            final Project project = GateSupport.project(projectFile);
            final String effectiveUpstream = upstream != null ? upstream
                    : System.getenv("SOKAR_GATE_UPSTREAM");
            final GitGate gate = GateSupport.gate(project, effectiveUpstream);
            gate.initialise();

            // From the environment when a task started this gate, so the container and the gate
            // agree on it. Never from an argument: a command line is visible in the host's
            // process list, and this token authenticates pushes.
            final String supplied = System.getenv("SOKAR_GATE_TOKEN");
            final TaskToken token = supplied == null || supplied.isBlank()
                    ? TaskToken.mint() : new TaskToken(supplied);
            try (GitHttpServer server = new GitHttpServer(
                    new InetSocketAddress(InetAddress.getByName(address), port),
                    gate.mirror(), token, new GitSubprocess())) {

                server.start();
                writePidFile(err);
                out.println("mirror    " + gate.mirror());
                out.println("mode      " + gate.mode().name().toLowerCase());
                out.println("url       http://" + address + ":" + server.port() + "/"
                        + project.name() + ".git");
                // Abbreviated: this line goes to gate.log, which the daemon streams to whatever
                // is tailing it. The container is given the token through its environment.
                out.println("token     " + token.abbreviate());
                out.flush();

                if (seconds > 0) {
                    Thread.sleep(java.time.Duration.ofSeconds(seconds));
                } else {
                    Thread.currentThread().join();
                }
            }
            return 0;

        } catch (GateException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
