package org.fuin.sokar.clearance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.wire.varlink.VarlinkServer;

/**
 * The clearance hub, reachable over varlink.
 * <p>
 * Three methods, and the split between them is the architecture:
 * <ul>
 * <li>{@code Report} is called by the NFLOG reader, which runs inside the container's network
 *     namespace and cannot talk to the session bus from there.</li>
 * <li>{@code Subscribe} streams events to whatever wants to watch - a desktop client, a terminal,
 *     later a GUI. It is the reason this is varlink rather than a pipe: a pipe has exactly one
 *     reader, and the events are interesting to more than one. Both the questions and the answers
 *     go out on it; an answer is the same event carrying a {@code verdict}.</li>
 * <li>{@code Verdict} lets a subscriber answer, so a decision can come from somewhere other than
 *     the notification that Sokar itself raised.</li>
 * </ul>
 */
public class ClearanceService implements AutoCloseable {

    /** Interface name this service implements. */
    public static final String INTERFACE = "org.fuin.sokar.Clearance1";

    private final VarlinkServer server;

    private final ClearanceHub hub;

    private final List<Backlog> subscribers =
            new CopyOnWriteArrayList<>();

    /**
     * Binds the socket and registers the methods.
     *
     * @param socket Where to create the socket.
     * @param hub Decides what happens to each reported connection.
     */
    public ClearanceService(Path socket, ClearanceHub hub) {

        this.hub = hub;
        this.server = new VarlinkServer(socket, INTERFACE);

        // Every decision is broadcast as well, carrying the fields of the question plus what was
        // decided. A subscriber that saw a question and never sees an answer cannot tell a prompt
        // that ran out from one still on screen, and the one that ran out is the one that needs
        // showing: nothing will ask again.
        hub.onDecision(decision -> broadcast(decision.event()));

        server.method("Report", (parameters, replies) -> {
            final String destination = String.valueOf(parameters.get("destination"));
            final String protocol = String.valueOf(parameters.get("protocol"));
            final int port = parameters.get("port") instanceof Number number ? number.intValue() : 0;
            // What was blocked, as read, and nothing its sender added: forwarded as it came, a report carrying
            // 'verdict' and 'source' read to an interface like an answer.
            final Map<String, Object> event = new LinkedHashMap<>();
            event.put("destination", destination);
            event.put("port", port);
            event.put("protocol", protocol);
            broadcast(event);
            final String shown = port == 0 ? destination : destination + ":" + port;
            final Verdict verdict = hub.handle(new Blocked(destination, port, protocol, shown));
            replies.last(Map.of("verdict", verdict.name().toLowerCase()));
        });

        server.method("Subscribe", (parameters, replies) -> {
            if (!replies.streaming()) {
                // Answering a non-streaming Subscribe with one event would look like it worked and
                // then deliver nothing ever again.
                replies.last(Map.of("error", "Subscribe requires more=true"));
                return;
            }
            final Backlog queue = new Backlog(1024);
            subscribers.add(queue);
            try {
                // Until its client goes: a subscriber that left was waited for until the next event, for ever when
                // none came.
                while (replies.open()) {
                    final Map<String, Object> event = queue.poll(1, TimeUnit.SECONDS);
                    if (event != null) {
                        replies.more(event);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                // The subscriber went away, which is the normal way a subscription ends.
            } finally {
                subscribers.remove(queue);
            }
        });

        server.method("Verdict", (parameters, replies) -> {
            final String key = String.valueOf(parameters.get("key"));
            final boolean allow = Boolean.TRUE.equals(parameters.get("allow"));
            hub.decide(key, String.valueOf(parameters.get("address")),
                    allow ? Verdict.ALLOW : Verdict.DENY);
            replies.last(Map.of("ok", Boolean.TRUE));
        });
    }

    /**
     * Returns the key that identifies one blocked connection.
     * <p>
     * Public and used from both ends on purpose. A subscriber has to send this back with its
     * verdict, and it is derived from fields the event already carries - so a client could build
     * it, and would then be one separator away from answering a prompt that does not exist while
     * the task stays blocked. Deriving it here means there is one derivation.
     *
     * @param protocol {@code tcp} or {@code udp}.
     * @param destination Host or address the task asked for.
     * @param port Port, or {@code 0} when there is none.
     * @return The key.
     */
    public static String key(String protocol, String destination, int port) {
        return protocol + "/" + destination + "/" + port;
    }

    /**
     * Sends an event to every subscriber, for one that did not arrive through {@code Report}.
     * <p>
     * The watcher has two ways in. When it starts its own reader, events arrive as {@code Report}
     * calls and are broadcast on the way past. When the reader hook is already running inside the
     * container, the watcher <em>follows the file</em> that hook appends to and decides from there
     * - and that path reached the hub without ever reaching a subscriber. Measured: a client
     * subscribed to a live task and saw nothing at all, while the log beside it recorded the
     * decisions. The point of this service is that these events are interesting to more than one
     * thing, so both ways in have to feed it.
     *
     * @param event The event, as the reader wrote it.
     */
    public void publish(Map<String, Object> event) {
        broadcast(new LinkedHashMap<>(event));
    }

    private void broadcast(Map<String, Object> event) {
        // Never waits: a subscriber that has stopped reading must not block the reader, which is
        // on the path of every dropped packet. What does not fit is counted and told.
        subscribers.forEach(queue -> queue.offer(event));
    }

    /**
     * Returns the socket path.
     *
     * @return Path subscribers connect to.
     */
    public Path socketPath() {
        return server.socketPath();
    }

    /**
     * Returns the number of current subscribers.
     *
     * @return Subscriber count.
     */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Starts serving on a virtual thread.
     *
     * @return The thread, so a caller can join it.
     */
    public Thread start() {
        return Thread.ofVirtual().start(server);
    }

    @Override
    public void close() {
        server.close();
    }
}
