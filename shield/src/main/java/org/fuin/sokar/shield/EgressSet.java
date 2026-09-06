package org.fuin.sokar.shield;

import java.util.List;

/**
 * A curated group of hosts a project can opt into by name.
 * <p>
 * Written once and reviewed once, so that "reaching npm" means the same thing in every project
 * instead of each one re-deriving it and getting it subtly wrong. A set is data, read from a
 * directory, so one can be added or corrected without rebuilding anything.
 *
 * @param name Name a project writes in its {@code egress.sets} list.
 * @param label Human-readable name, for listings.
 * @param domains Hosts the set grants, in the order declared.
 */
public record EgressSet(String name, String label, List<String> domains) {

    /**
     * Constructor.
     *
     * @param name Set name.
     * @param label Human-readable name.
     * @param domains Hosts granted.
     */
    public EgressSet {
        domains = List.copyOf(domains);
    }
}
