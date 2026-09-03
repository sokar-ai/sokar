package org.fuin.sokar.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.clearance.ClearanceException;
import org.fuin.sokar.clearance.ClearanceHub;
import org.fuin.sokar.clearance.ClearancePrompt;
import org.fuin.sokar.clearance.ClearanceRequest;
import org.fuin.sokar.clearance.DesktopPrompt;
import org.fuin.sokar.clearance.Verdict;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.shield.EgressPolicy;
import org.fuin.sokar.shield.NftRuleset;
import org.fuin.sokar.wire.Json;
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

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final EgressPolicy policy = new EgressPolicy(new ProcessCommandRunner(), containerPid);

        Process reader = null;
        try (AutoCloseable prompt = prompt()) {

            final ClearanceHub hub = new ClearanceHub(project, (ClearancePrompt) prompt, address -> {
                policy.allow(address);
                out.println("allowed   " + address);
                out.flush();
            });

            reader = startReader();
            out.println("watching  NFLOG group " + group + " for project " + project);
            out.flush();

            int decisions = 0;
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(reader.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    final Verdict verdict = handle(hub, line, err);
                    if (verdict != null) {
                        out.println("decided   " + verdict.name().toLowerCase() + "  " + line);
                        out.flush();
                        if (count > 0 && ++decisions >= count) {
                            break;
                        }
                    }
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

    private Process startReader() throws IOException {
        // The reader is this same binary, re-invoked inside the namespace. One artifact, and the
        // reader is therefore always the same version as the watcher.
        final String self = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IOException("Cannot determine the path of this binary"));
        final List<String> command = EgressPolicy.inNamespace(containerPid,
                List.of(self, "shield", "read", "--group", String.valueOf(group)));
        return new ProcessBuilder(command).redirectErrorStream(false).start();
    }

    private Verdict handle(ClearanceHub hub, String line, PrintWriter err) {
        try {
            if (!(Json.parse(line) instanceof Map<?, ?> event)) {
                return null;
            }
            final String destination = String.valueOf(event.get("destination"));
            final String protocol = String.valueOf(event.get("protocol"));
            final int port = event.get("port") instanceof Number number ? number.intValue() : 0;
            final String shown = port == 0 ? destination : destination + ":" + port;
            return hub.handle(protocol + "/" + destination + "/" + port, destination, shown, protocol);
        } catch (RuntimeException ex) {
            // A line the reader could not have produced is not worth stopping for.
            err.println("sokar: ignoring unreadable event: " + line);
            err.flush();
            return null;
        }
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
