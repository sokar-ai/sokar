package org.fuin.sokar.machines;

import java.util.ArrayList;
import java.util.List;

/**
 * What to rent.
 *
 * @param name What to call the server. Unique per run, because the sweep deletes by label and a
 *     collision only confuses a person reading the list.
 * @param os The {@code os} label of the snapshot to boot, such as {@code ubuntu}.
 * @param serverTypes Hetzner types to try, in the order they should be tried. The first that a
 *     location in the zone can actually serve is the one created.
 * @param user Who to connect as once it is up.
 * @param credential The key to connect with, and the one the server is created for.
 * @param keep Whether to leave it running, for debugging.
 */
public record Spec(String name, String os, List<String> serverTypes, String user,
        Credential credential, boolean keep) {

    /**
     * What to create when a caller names nothing.
     * <p>
     * One type, and the one a leg has always used: a default that quietly picked something
     * smaller would change what every leg is tested on without anybody choosing it. Preferring
     * cheaper machines is a decision a workflow makes out loud, by naming the order it wants.
     * <p>
     * <strong>There is no fallback here, and that is a consequence rather than a choice.</strong>
     * A snapshot only restores onto a disk at least as big as the one it was taken on, and the
     * current images were built on a 320 GB machine because waiting on two cores was costing more
     * than the flexibility was worth. Only types with that much disk can boot them. Rebuilding the
     * images on a small machine is what would give the fallback back.
     */
    public static final List<String> DEFAULT_TYPES = List.of("cpx42");

    /**
     * Compact constructor, fixing the order and refusing an empty list.
     *
     * @param name What to call the server.
     * @param os The snapshot's {@code os} label.
     * @param serverTypes Types to try, in order.
     * @param user Who to connect as.
     * @param credential The key.
     * @param keep Whether to leave it running.
     */
    public Spec {
        if (serverTypes == null || serverTypes.isEmpty()) {
            throw new IllegalArgumentException("Name at least one server type to try.");
        }
        serverTypes = List.copyOf(serverTypes);
    }

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
        return new Spec(name, os, DEFAULT_TYPES, user, credential, false);
    }

    /**
     * Returns the same spec, trying these types in this order instead.
     *
     * @param types Types to try, cheapest or most wanted first.
     * @return A spec that tries them in order.
     */
    public Spec tryingInOrder(List<String> types) {
        return new Spec(name, os, types, user, credential, keep);
    }

    /**
     * Reads an order from a comma-separated list, as a workflow passes one.
     *
     * @param types Such as {@code "cx23,cx33,cpx12"}, or blank for the default.
     * @return The types, in the order given.
     */
    public static List<String> order(String types) {
        if (types == null || types.isBlank()) {
            return DEFAULT_TYPES;
        }
        final List<String> wanted = new ArrayList<>();
        for (final String each : types.split(",")) {
            if (!each.isBlank()) {
                wanted.add(each.strip());
            }
        }
        return wanted.isEmpty() ? DEFAULT_TYPES : List.copyOf(wanted);
    }

    /**
     * Returns the same spec, left running afterwards.
     *
     * @return A spec that keeps its server.
     */
    public Spec kept() {
        return new Spec(name, os, serverTypes, user, credential, true);
    }
}
