package org.fuin.sokar.clearance;

/**
 * One connection the firewall stopped, as it was reported.
 * <p>
 * A record rather than four parameters because both ways into the hub carry the same four fields -
 * the reader calling {@code Report}, and the watcher following the file the reader hook writes -
 * and a positional list of that length is where two call sites drift apart.
 *
 * @param destination Host or address the task asked for, and what an allow adds to the set.
 * @param port Port, or {@code 0} when there is none.
 * @param protocol Protocol name, for example {@code tcp}.
 * @param shown How it should be shown to the operator, which may carry a resolved name.
 * @param name The name the container was answered with, or {@code null} when nothing resolved it.
 *        A grant is made for a name, and an address on its own can never match one.
 */
public record Blocked(String destination, int port, String protocol, String shown,
        @org.jspecify.annotations.Nullable String name) {

    /**
     * Constructor for a destination nothing resolved a name for.
     *
     * @param destination Host or address the task asked for.
     * @param port Port, or {@code 0} when there is none.
     * @param protocol Protocol name.
     * @param shown How it should be shown to the operator.
     */
    public Blocked(String destination, int port, String protocol, String shown) {
        this(destination, port, protocol, shown, null);
    }

    /**
     * Returns the key this destination is decided under.
     *
     * @return The key.
     */
    public String key() {
        return ClearanceService.key(protocol, destination, port);
    }

    /**
     * Rebuilds what a key was made of, for a verdict that arrived without the event.
     * <p>
     * A client may answer a prompt the hub has not seen: the event reaches subscribers before the
     * hub decides, so an answer can overtake it. The audit record still has to name a destination,
     * and the key is the only description there is.
     *
     * @param key Key as {@link #key()} produced it.
     * @param address Address the client asked to allow.
     * @return What the key describes.
     */
    static Blocked fromKey(String key, String address) {
        final String[] parts = key.split("/");
        if (parts.length != 3) {
            return new Blocked(address, 0, "", key);
        }
        int port = 0;
        try {
            port = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ex) {
            // A key Sokar did not write. The destination is still worth recording.
        }
        return new Blocked(parts[1], port, parts[0], port == 0 ? parts[1] : parts[1] + ":" + port);
    }
}
