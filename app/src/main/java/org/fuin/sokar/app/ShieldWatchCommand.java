package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.fuin.sokar.clearance.Blocked;
import org.fuin.sokar.clearance.ClearanceException;
import org.fuin.sokar.clearance.ClearanceHub;
import org.fuin.sokar.clearance.ClearanceJournal;
import org.fuin.sokar.clearance.ClearancePrompt;
import org.fuin.sokar.clearance.ClearanceRequest;
import org.fuin.sokar.clearance.ClearanceService;
import org.fuin.sokar.clearance.Decision;
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

    @Option(names = "--task", paramLabel = "<name>",
            description = "Task name, shown in the prompt beside the project.")
    private String task = "";

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

    @Option(names = "--events", paramLabel = "<file>",
            description = "Follows an events file the reader hook is writing, instead of starting"
                    + " a reader. Use this when the container already has one.")
    private Path events;

    @Option(names = "--pid-file", paramLabel = "<file>",
            description = "Writes this process's id here, so the poststop hook can reap it.")
    private Path pidFile;

    @Option(names = "--journal", paramLabel = "<file>",
            description = "Where decisions are recorded and read back from. Default: beside the"
                    + " clearance socket.")
    private Path journal;

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

            final ClearanceJournal record = new ClearanceJournal(journalPath());

            final ClearanceHub hub = new ClearanceHub(project, task,
                    // Wrapped so the task can say it is waiting for a person. Written when the
                    // question is put and removed when it is answered, which is the work's own
                    // signal - a quiet task is not a waiting one, and anything inferred from a
                    // clock would be wrong in the direction that costs the most.
                    request -> {
                        final org.fuin.sokar.wire.Waiting waiting =
                                new org.fuin.sokar.wire.Waiting(socketPath().getParent());
                        waiting.asking(request.destination());
                        try {
                            return ((ClearancePrompt) prompt).ask(request);
                        } finally {
                            waiting.answered();
                        }
                    },
                    (address, name) -> {
                        try {
                            policy.allow(address);
                            // Written down at the one moment both are known. Resolving the name
                            // again later to find out what to withdraw was rejected in B12: a
                            // CDN, GeoDNS or plain round-robin answers Sokar and the container
                            // differently, and the addresses that differ are exactly the ones
                            // that would be left open.
                            if (name != null) {
                                try {
                                    org.fuin.sokar.wire.GrantedAddresses.add(
                                            socketPath().getParent(), name, address);
                                } catch (java.io.IOException ex) {
                                    // NOT the same failure as the allow not taking effect, and it
                                    // must not borrow that sentence: the host IS open. What is
                                    // lost is the pair a later withdrawal would need, so the
                                    // withdrawal will say it could not find the address rather
                                    // than silently leaving it open.
                                    err.println("sokar: allowed " + address + " but could not"
                                            + " record that it is " + name + ": " + ex.getMessage()
                                            + " - withdrawing " + name + " will not remove it");
                                    err.flush();
                                }
                            }
                            out.println("allowed   " + address);
                        } catch (RuntimeException ex) {
                            // Never silent. A verdict of allow that did not take effect leaves the
                            // operator believing they unblocked something they did not, and the
                            // agent failing for a reason the log says was resolved.
                            err.println("sokar: DECIDED ALLOW BUT COULD NOT APPLY IT for " + address
                                    + ": " + ex.getMessage());
                            err.flush();
                        }
                        out.flush();
                    });

            hub.onDecision(decision -> {
                out.println("decided   " + decision.verdict().name().toLowerCase(Locale.ROOT)
                        + "  " + decision.shown() + "  by " + decision.source());
                out.flush();
                try {
                    record.record(decision);
                } catch (ClearanceException ex) {
                    // Never silent, for the same reason an allow that did not take effect is not:
                    // the audit record is what says a destination was refused rather than never
                    // asked about, and a gap in it is invisible from the file itself.
                    err.println("sokar: DECIDED " + decision.verdict().name().toLowerCase(Locale.ROOT)
                            + " FOR " + decision.shown() + " BUT COULD NOT RECORD IT: "
                            + ex.getMessage());
                    err.flush();
                }
                if (count > 0 && decisions.incrementAndGet() >= count) {
                    enough.countDown();
                }
            });

            // Asked afresh on every event: a grant is made while the task runs, usually seconds
            // after an agent was refused something, so a list read at start would never hold the
            // one that matters.
            hub.granted(name -> org.fuin.sokar.wire.GrantedNames.covers(
                    socketPath().getParent(), name));

            // Before anything is followed. A resumed task re-reads the events file from the start,
            // so a destination decided in the previous run reaches the hub again within seconds -
            // and without its decisions back, that is a second prompt for a question the operator
            // has already answered, or a second chance for an agent whose first attempt was
            // refused.
            final List<Decision> earlier = record.read();
            out.println("journal   " + record.file()
                    + (earlier.isEmpty() ? "" : ", taking back " + earlier.size() + " decisions"));
            out.flush();
            hub.restore(earlier);

            try (ClearanceService service = new ClearanceService(socketPath(), hub)) {

                service.start();
                out.println("clearance " + service.socketPath());

                writePidFile(err);

                if (events != null) {
                    // The reader hook already started one inside the container and is appending to
                    // this file. Starting a second reader would bind the same NFLOG group twice
                    // and split the events between them.
                    out.println("following " + events + " for project " + project);
                    out.flush();
                    follow(hub, service, out, err, enough);
                } else {
                    reader = startReader(service.socketPath());
                    out.println("watching  NFLOG group " + group + " for project " + project);
                    out.flush();
                    if (count > 0) {
                        enough.await();
                    } else {
                        reader.waitFor();
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

    /**
     * Turns one line of the reader's output into a decision.
     *
     * @return The verdict, or {@code null} if the line was not an event.
     */
    private Verdict handle(ClearanceHub hub, ClearanceService service, String line,
            PrintWriter err) {
        try {
            if (!(org.fuin.sokar.wire.Json.parse(line) instanceof java.util.Map<?, ?> event)) {
                return null;
            }
            final String destination = String.valueOf(event.get("destination"));
            final String protocol = String.valueOf(event.get("protocol"));
            final int port = event.get("port") instanceof Number number ? number.intValue() : 0;
            // A bare address is not something an operator can judge. The resolver logged what
            // it answered, which is the only place to turn the number back into the name the
            // agent asked for; the address stays visible because the decision is about it.
            final String name = events == null ? null
                    : org.fuin.sokar.shield.ResolvedNames
                            .lookup(events.resolveSibling("dnsmasq.log"), destination)
                            .orElse(null);
            final String shownAddress = port == 0 ? destination : destination + ":" + port;
            final String shown = name == null ? shownAddress
                    : name + (port == 0 ? "" : ":" + port) + " (" + destination + ")";
            final Blocked blocked = new Blocked(destination, port, protocol, shown, name);

            // Published before it is decided: a subscriber - a terminal watching, or the daemon
            // carrying prompts to an interface - wants the question, and the line below blocks
            // until somebody answers it.
            //
            // Rebuilt rather than forwarded, and it carries the key: a client answering this
            // has to name the same destination the hub deduplicates on, and deriving that from
            // three separate fields at the far end is a rule in two places that can drift. The
            // resolved name comes with it, because an address alone is not something an operator
            // can judge.
            final java.util.Map<String, Object> prompt = new java.util.LinkedHashMap<>();
            prompt.put("key", blocked.key());
            prompt.put("destination", destination);
            prompt.put("address", destination);
            prompt.put("protocol", protocol);
            prompt.put("port", port);
            prompt.put("name", name == null ? "" : name);
            prompt.put("shown", shown);
            prompt.put("project", project);
            service.publish(prompt);

            return hub.handle(blocked);
        } catch (RuntimeException ex) {
            // A line the reader could not have produced is not worth stopping for; a partially
            // written last line is normal when following a file that is still being appended to.
            return null;
        }
    }

    /**
     * Returns where decisions are recorded.
     * <p>
     * Beside the socket only when nothing said otherwise, which is the case for a watcher started
     * by hand. A task's own watcher is pointed at the state directory instead: everything beside
     * the socket is under the runtime directory, which {@code task stop --remove} deletes and the
     * kernel clears at logout, and a record of what an agent tried to reach that goes when the
     * task goes is not an audit record.
     *
     * @return The journal file.
     */
    private Path journalPath() {
        return journal != null ? journal : socketPath().resolveSibling("clearance.jsonl");
    }

    private Path socketPath() {
        return socket != null ? socket
                : Path.of(System.getProperty("java.io.tmpdir")).resolve("sokar-clearance-"
                        + containerPid + ".sock");
    }

    private void writePidFile(PrintWriter err) {
        if (pidFile == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(pidFile,
                    String.valueOf(ProcessHandle.current().pid()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // Not fatal: it only means poststop cannot reap this process, and the runtime
            // directory is cleared at logout anyway.
            err.println("sokar: cannot write " + pidFile + ": " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Follows an events file the reader hook is appending to.
     * <p>
     * A file rather than a socket, because the two ends have different lifetimes: the reader
     * starts with the container and the watcher may be started, stopped and restarted while the
     * task runs. Re-reading from the beginning on start is deliberate - a destination decided
     * before the watcher existed should still be applied.
     */
    private void follow(ClearanceHub hub, ClearanceService service, PrintWriter out,
            PrintWriter err,
            java.util.concurrent.CountDownLatch enough) throws IOException, InterruptedException {

        long position = 0;
        while (enough.getCount() > 0) {
            if (java.nio.file.Files.exists(events)) {
                try (var lines = java.nio.file.Files.lines(events)) {
                    final long[] seen = { 0 };
                    final long start = position;
                    lines.forEach(line -> {
                        if (seen[0]++ >= start) {
                            handle(hub, service, line, err);
                        }
                    });
                    position = seen[0];
                }
            }
            if (enough.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                return;
            }
        }
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
