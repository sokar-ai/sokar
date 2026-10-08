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
import org.jspecify.annotations.Nullable;
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
public class GateServeCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = { "-p", "--project" }, paramLabel = "<name>", required = true,
            description = "Project name, as 'sokar project list' prints it.")
    private String projectName;

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories. Default: the project's own.")
    private @Nullable String repository;

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream repository, used only when approving.")
    private @Nullable String upstream;

    @Option(names = "--address", paramLabel = "<ip>",
            description = "Address to bind. Default: ${DEFAULT-VALUE}")
    private String address = "127.0.0.1";

    @Option(names = "--port", paramLabel = "<n>",
            description = "Port to bind, or 0 to pick one. Default: ${DEFAULT-VALUE}")
    private int port;

    @Option(names = "--pid-file", paramLabel = "<file>",
            description = "Writes this process's id here, so the poststop hook can reap it.")
    private @Nullable Path pidFile;

    @Option(names = "--ref", paramLabel = "<ref>",
            description = "The one ref a push may update, the task's own. Default: any ref under refs/sokar/incoming/.")
    private @Nullable String ref;

    @Option(names = "--pass-on",
            description = "Passes a push to the task's own ref on to the upstream at once, as sokar/<task>, and fetches"
                    + " from the upstream before the agent fetches: an online task's gate. Needs --ref.")
    private boolean passOn;
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
            org.fuin.sokar.wire.HelperPid.record(pidFile);
        } catch (java.io.IOException ex) {
            err.println("sokar: cannot write " + pidFile + ": " + ex.getMessage());
            err.flush();
        }
    }

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {

            final Project project = GateSupport.byName(context, projectName);
            final String effectiveUpstream = upstream != null ? upstream
                    : System.getenv("SOKAR_GATE_UPSTREAM");
            final GitGate gate = GateSupport.gate(context, project, GateSupport.repository(project, repository),
                    effectiveUpstream, null);
            gate.initialize();

            // From the environment when a task started this gate, so the container and the gate
            // agree on it. Never from an argument: a command line is visible in the host's
            // process list, and this token authenticates pushes.
            final String supplied = System.getenv("SOKAR_GATE_TOKEN");
            final TaskToken token = supplied == null || supplied.isBlank()
                    ? TaskToken.mint() : new TaskToken(supplied);
            if (passOn && (ref == null || !ref.startsWith(GitGate.INCOMING))) {
                err.println("sokar: --pass-on needs --ref " + GitGate.INCOMING + "<task>, the one ref passed on");
                err.flush();
                return 2;
            }
            final String branch = passOn ? "sokar/" + java.util.Objects.requireNonNull(ref)
                    .substring(GitGate.INCOMING.length()) : null;
            final GitHttpServer.Upstream through = branch == null ? null : new GitHttpServer.Upstream() {
                @Override
                public void refresh() {
                    final String behind = gate.refresh();
                    if (!behind.isEmpty()) {
                        System.err.println(behind);
                    }
                }

                @Override
                public @Nullable String passOn(String taken, String commit) {
                    return gate.passOn(branch, commit);
                }
            };
            try (GitHttpServer server = new GitHttpServer(
                    new InetSocketAddress(InetAddress.getByName(address), port),
                    gate.mirror(), token, new GitSubprocess(), ref, through, GitHttpServer.PACE)) {

                server.start();
                writePidFile(err);
                out.println("mirror    " + gate.mirror());
                out.println("mode      " + gate.mode().name().toLowerCase()
                        + (branch == null ? "" : ", passing " + ref + " on as " + branch));
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
