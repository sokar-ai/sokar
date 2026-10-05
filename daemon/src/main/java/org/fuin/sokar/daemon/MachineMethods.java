package org.fuin.sokar.daemon;

import static org.fuin.sokar.daemon.SokarDaemon.*;

import java.util.List;
import java.util.Map;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.wire.varlink.VarlinkServer;

/**
 * The daemon's methods for this machine itself.
 * <p>
 * One class per area, so a change to one area's methods is a change to its own file; {@link SokarDaemon} only
 * says which areas there are.
 */
final class MachineMethods {

    private MachineMethods() {
    }

    /**
     * Registers this area's methods.
     *
     * @param server Where.
     * @param context The machine.
     * @param inventory The daemon's one task inventory.
     * @param control The daemon's one task control.
     */
    @SuppressWarnings("unused")
    static void register(VarlinkServer server, SokarContext context, TaskInventory inventory, TaskControl control) {
        server.method("Doctor", (parameters, replies) -> {
            // The same probes the CLI prints, through the same object, so a machine cannot be
            // called ready here and unready there.
            final java.util.List<org.fuin.sokar.app.Probe> found =
                    org.fuin.sokar.app.DoctorCommand.probesFor(context);
            replies.last(Map.of("probes", found.stream()
                    .map(probe -> Map.of("name", probe.name(), "state", probe.state().name(),
                            "detail", probe.detail(),
                            "action", probe.action() == null ? "" : probe.action())).toList(),
                    "ready", org.fuin.sokar.app.DoctorCommand.ready(found)));
        });

        server.method("Node", (parameters, replies) -> {
            // Minted on first ask and kept. A client cannot work this out: a hostname has many
            // spellings and a forwarded socket looks nothing like your own tunnel to the same
            // place, so the same node can sit twice in a list of machines and deliver every
            // clearance question twice.
            replies.last(Map.of("id",
                    org.fuin.sokar.app.NodeIdentity.of(context.paths().nodeIdFile())));
        });
    }
}
