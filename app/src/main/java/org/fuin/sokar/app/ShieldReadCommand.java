package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.shield.BlockedConnection;
import org.fuin.sokar.shield.NetlinkException;
import org.fuin.sokar.shield.NflogReader;
import org.fuin.sokar.shield.NftRuleset;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Reads dropped packets from an NFLOG group and prints them as line JSON.
 * <p>
 * Started by the reader hook, which enters the container's network namespace first. It is not a
 * command an operator would normally type, but it is genuinely useful when diagnosing a container
 * that cannot reach something, so it is not hidden.
 * <p>
 * This is also where the netlink downcalls are exercised in the shipped binary, which is how the
 * hand-written FFM registrations in {@code sokar-shield} get verified rather than assumed.
 */
@Command(name = "read",
        mixinStandardHelpOptions = true,
        description = "Reads blocked connections from the container's firewall log.")
public class ShieldReadCommand implements Callable<Integer> {

    @Option(names = "--group", paramLabel = "<n>",
            description = "NFLOG group to bind. Default: ${DEFAULT-VALUE}")
    private int group = NftRuleset.NFLOG_GROUP;

    @Option(names = "--count", paramLabel = "<n>",
            description = "Stop after this many events. Zero means run until killed.")
    private int count;

    @Option(names = "--all",
            description = "Reports every dropped packet, including multicast and link-local noise.")
    private boolean all;

    @Option(names = "--report-to", paramLabel = "<socket>",
            description = "Sends each event to a clearance service over varlink instead of stdout.")
    private java.nio.file.Path reportTo;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws java.io.IOException {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try (NflogReader reader = new NflogReader(group);
                org.fuin.sokar.clearance.varlink.VarlinkClient client = connect()) {

            final int[] seen = { 0 };
            reader.readUntilStopped(event -> {
                if (!all && !event.worthAsking()) {
                    // Router solicitations and multicast listener reports are dropped by the
                    // default-deny chain too. Passing them on would bury the events that matter.
                    return;
                }
                if (client == null) {
                    out.println(event.toJson());
                    out.flush();
                } else {
                    report(client, event, err);
                }
                if (count > 0 && ++seen[0] >= count) {
                    reader.stop();
                }
            });
            return 0;

        } catch (NetlinkException ex) {
            // Almost always: started outside the container's network namespace, where there is no
            // CAP_NET_ADMIN. Say that rather than printing an errno.
            err.println("sokar: " + ex.getMessage());
            err.println("sokar: the reader must run inside the container's network namespace");
            err.flush();
            return 69;
        }
    }

    private org.fuin.sokar.clearance.varlink.VarlinkClient connect() {
        return reportTo == null ? null
                : new org.fuin.sokar.clearance.varlink.VarlinkClient(reportTo);
    }

    private void report(org.fuin.sokar.clearance.varlink.VarlinkClient client,
            BlockedConnection event, PrintWriter err) {
        try {
            client.call(org.fuin.sokar.clearance.ClearanceService.INTERFACE + ".Report",
                    java.util.Map.of(
                            "prefix", event.prefix(),
                            "protocol", event.protocolName(),
                            "destination", event.destination(),
                            "port", Integer.valueOf(event.port())));
        } catch (RuntimeException ex) {
            // The hub going away must not stop the reader: the firewall keeps dropping either way,
            // and a reader that exits takes the audit trail with it.
            err.println("sokar: cannot report " + event.describe() + ": " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Returns the event type this command reports, so the class is not mistaken for unused.
     *
     * @return Event class.
     */
    static Class<BlockedConnection> eventType() {
        return BlockedConnection.class;
    }
}
