package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.clearance.ClearanceException;
import org.fuin.sokar.clearance.ClearanceHub;
import org.fuin.sokar.clearance.ClearanceService;
import org.fuin.sokar.clearance.ClearancePrompt;
import org.fuin.sokar.clearance.ClearanceRequest;
import org.fuin.sokar.clearance.DesktopPrompt;
import org.fuin.sokar.clearance.Verdict;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.shield.EgressPolicy;
import org.fuin.sokar.shield.NftRuleset;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Watches a container's blocked connections and asks the operator about each new destination.
 * <p>
 * <strong>This runs on the host, not inside the container's namespaces, and that split is
 * forced.</strong> Reading NFLOG needs {@code CAP_NET_ADMIN} in the container's network namespace,
 * which means entering the operator's user namespace through {@code podman unshare}. Inside that
 * namespace the process believes it is uid 0, and dbus-java's EXTERNAL authentication sends that
 * uid to the session bus - which sees the real uid and refuses. So the reader runs in the
 * namespace and reports events as line JSON, and the part that talks to the operator stays out
 * here where it can authenticate.
 * <p>
 * An allow is added to the live nftables set, so the agent's next attempt succeeds. Reloading the
 * ruleset instead would drop conntrack state and kill every connection it already had open.
 * <p>
 * The two halves talk varlink over a unix socket rather than a pipe. A pipe has exactly one
 * reader, and these events are interesting to more than one thing: the prompt, a terminal watching
 * along, later a GUI. {@code Subscribe} exists for those, and it is why the transport is a
 * protocol rather than a stream of lines.
 */
@Command(name = "watch",
        mixinStandardHelpOptions = true,
        description = "Prompts for each blocked connection and allows the ones you approve.")
public class ShieldWatchCommand implements Callable<Integer> {

    @Option(names = "--project", paramLabel = "<name>", required = true,
            description = "Project name, shown in the prompt.")
    private String project;

    @Option(names = "--pid", paramLabel = "<n>", required = true,
            description = "Host process id of the container's init process.")
    private long containerPid;

    @Option(names = "--group", paramLabel = "<n>",
            description = "NFLOG group to bind. Default: ${DEFAULT-VALUE}")
    private int group = NftRuleset.NFLOG_GROUP;

    @Option(names = "--timeout", paramLabel = "<seconds>",
            description = "How long to wait for an answer. Default: ${DEFAULT-VALUE}")
    private int timeoutSeconds = 60;

    @Option(names = "--deny-all",
            description = "Answers every prompt with Deny, without asking.")
    private boolean denyAll;

    @Option(names = "--allow-all",
            description = "Answers every prompt with Allow, without asking.")
    private boolean allowAll;

    @Option(names = "--count", paramLabel = "<n>",
            description = "Stop after this many decisions. Zero means run until killed.")
    private int count;

    @Option(names = "--socket", paramLabel = "<path>",
            description = "Where to create the clearance socket. Default: beside the container state.")
    private Path socket;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final EgressPolicy policy = new EgressPolicy(new ProcessCommandRunner(), containerPid);

        Process reader = null;
        try (AutoCloseable prompt = prompt()) {

            final java.util.concurrent.atomic.AtomicInteger decisions =
                    new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.CountDownLatch enough = new java.util.concurrent.CountDownLatch(1);

            final ClearanceHub hub = new ClearanceHub(project, request -> {
                final Verdict verdict = ((ClearancePrompt) prompt).ask(request);
                out.println("decided   " + verdict.name().toLowerCase() + "  " + request.destination());
                out.flush();
                if (count > 0 && decisions.incrementAndGet() >= count) {
                    enough.countDown();
                }
                return verdict;
            }, address -> {
                policy.allow(address);
                out.println("allowed   " + address);
                out.flush();
            });

            try (ClearanceService service = new ClearanceService(socketPath(), hub)) {

                service.start();
                out.println("clearance " + service.socketPath());

                reader = startReader(service.socketPath());
                out.println("watching  NFLOG group " + group + " for project " + project);
                out.flush();

                if (count > 0) {
                    enough.await();
                } else {
                    reader.waitFor();
                }
            }
            return 0;

        } catch (ClearanceException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        } catch (Exception ex) {
            err.println("sokar: " + ex);
            err.flush();
            return 70;
        } finally {
            if (reader != null) {
                reader.destroy();
            }
        }
    }

    private Path socketPath() {
        return socket != null ? socket
                : Path.of(System.getProperty("java.io.tmpdir")).resolve("sokar-clearance-"
                        + containerPid + ".sock");
    }

    private Process startReader(Path clearanceSocket) throws IOException {
        // The reader is this same binary, re-invoked inside the namespace. One artifact, and the
        // reader is therefore always the same version as the watcher.
        final String self = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IOException("Cannot determine the path of this binary"));
        final List<String> command = EgressPolicy.inNamespace(containerPid,
                List.of(self, "shield", "read", "--group", String.valueOf(group),
                        "--report-to", clearanceSocket.toString()));
        return new ProcessBuilder(command).inheritIO().start();
    }

    private AutoCloseable prompt() {
        if (allowAll || denyAll) {
            return new FixedPrompt(allowAll ? Verdict.ALLOW : Verdict.DENY);
        }
        return new DesktopPrompt(Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * Answers every question the same way, without asking anyone.
     */
    private static final class FixedPrompt implements ClearancePrompt, AutoCloseable {

        private final Verdict verdict;

        private FixedPrompt(Verdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public Verdict ask(ClearanceRequest request) {
            return verdict;
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
