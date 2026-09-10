package org.fuin.sokar.machines;

/**
 * What to rent.
 *
 * @param name What to call the server. Unique per run, because the sweep deletes by label and a
 *     collision only confuses a person reading the list.
 * @param os The {@code os} label of the snapshot to boot, such as {@code ubuntu}.
 * @param serverType The Hetzner type, such as {@code cpx41}.
 * @param user Who to connect as once it is up.
 * @param credential The key to connect with, and the one the server is created for.
 * @param keep Whether to leave it running, for debugging.
 */
public record Spec(String name, String os, String serverType, String user, Credential credential,
        boolean keep) {

    /**
     * The type every leg has used so far.
     * <p>
     * Named here rather than in a workflow so that changing it is one edit and is reviewable.
     */
    public static final String DEFAULT_TYPE = "cpx41";

    /**
     * Returns a spec with the usual type and no keeping.
     *
     * @param name What to call the server.
     * @param os The snapshot's {@code os} label.
     * @param user Who to connect as.
     * @param credential The key.
     * @return The spec.
     */
    public static Spec of(String name, String os, String user, Credential credential) {
        return new Spec(name, os, DEFAULT_TYPE, user, credential, false);
    }

    /**
     * Returns the same spec, left running afterwards.
     *
     * @return A spec that keeps its server.
     */
    public Spec kept() {
        return new Spec(name, os, serverType, user, credential, true);
    }
}
