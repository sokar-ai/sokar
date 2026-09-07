package org.fuin.sokar.daemon;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.wire.varlink.VarlinkServer;

/**
 * Entry point of the {@code sokard} binary: the domain over a unix socket, for an interface that
 * never shells out to the CLI.
 * <p>
 * <strong>A socket, never a port.</strong> The socket is the only entry point, which makes remote
 * access a tunnelling problem rather than an authentication problem - there is no listener on any
 * interface to authenticate against in the first place. The filesystem decides who may connect:
 * the socket is owner-only, so another account on the same machine is refused by the kernel rather
 * than by a check this had to remember to write.
 * <p>
 * <strong>Varlink, because the agent contract already speaks it.</strong> Sokar drives every agent
 * over varlink, so the protocol, its client, its server and its streaming replies are already
 * built and already tested here. Introducing a second protocol for the same kind of local IPC
 * would be two things to get right instead of one.
 * <p>
 * <strong>The daemon is not a task's parent.</strong> It starts nothing and owns nothing: task
 * containers belong to the container runtime and their helpers are their own processes, recorded
 * in each task's state directory. Killing this leaves every running task exactly where it was,
 * which is what makes restarting it safe.
 */
public final class SokarDaemon {

    /** Varlink interface this serves. */
    public static final String INTERFACE = "org.fuin.sokar.Tasks";

    /** Name of the socket inside Sokar's runtime directory. */
    public static final String SOCKET = "sokard.sock";

    private SokarDaemon() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a server offering the calls, bound to the given socket.
     * <p>
     * Separate from {@link #main(String[])} so a test can drive the real thing over a real socket
     * rather than a stand-in: what matters about this is the wire, and a mock of the wire proves
     * nothing about it.
     *
     * @param context Where podman and the paths come from.
     * @param socket Where to bind.
     * @return The server, not yet running.
     */
    public static VarlinkServer serving(SokarContext context, Path socket) {
        final VarlinkServer server = new VarlinkServer(socket, INTERFACE);
        final TaskInventory inventory = new TaskInventory(context);

        // The same question 'sokar task list' asks, answered by the same code. A second
        // implementation is how the two come to disagree about what is running.
        server.method("List", (parameters, replies) -> {
            final List<Map<String, Object>> tasks =
                    inventory.tasks().stream().map(TaskInventory.Task::asMap).toList();
            replies.last(Map.of("tasks", tasks));
        });

        return server;
    }

    /**
     * Runs the daemon until it is stopped.
     *
     * @param args Ignored; the socket location comes from the environment, like everything else.
     */
    public static void main(final String[] args) {
        final SokarContext context = SokarContext.real();
        final Path socket = context.paths().xdg().runtime().resolve(SOCKET);
        try (VarlinkServer server = serving(context, socket)) {
            System.out.println("sokard listening on " + socket);
            System.out.flush();
            server.run();
        } catch (RuntimeException ex) {
            System.err.println("sokard: " + ex.getMessage());
            System.err.flush();
            System.exit(70);
        }
    }
}
