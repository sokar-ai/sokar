package org.fuin.sokar.core.project;

/**
 * How much the agent in a task container is trusted with the outside world.
 * <p>
 * The class decides the egress policy and whether an agent's work is reviewed before it reaches
 * the upstream. It is a property of the project, not of a single run: a task cannot raise it from
 * the command line.
 * <p>
 * Only {@link #OFFLINE} currently changes the egress policy; {@link #GUARDED} and {@link #ONLINE}
 * produce the same resolver configuration and the same firewall ruleset, and differ in git.
 */
public enum SecurityClass {

    /** No egress beyond what the base image needs. The agent never reaches an upstream. */
    OFFLINE,

    /**
     * Curated egress sets. The agent pushes to a local mirror it cannot see past, and an operator
     * approves each push before the gate forwards it upstream.
     */
    GUARDED,

    /**
     * The same egress as {@link #GUARDED}, and no gate: the agent's remote is the upstream, so
     * what it pushes lands there unreviewed. This is why the class requires an upstream and why a
     * project has to opt into it.
     */
    ONLINE;

    /**
     * Parses the value used in {@code project.yml}.
     *
     * @param value Case-insensitive name.
     * @return Matching class.
     * @throws ProjectException If the value names no known class.
     */
    public static SecurityClass parse(String value) {
        for (final SecurityClass candidate : values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new ProjectException("Unknown security class '" + value
                + "', expected one of: offline, guarded, online");
    }
}
