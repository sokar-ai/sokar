package org.fuin.sokar.core.project;

/**
 * How much the agent in a task container is trusted with the outside world.
 * <p>
 * The class decides the egress policy and whether the git gate will push to an upstream. It is a
 * property of the project, not of a single run: a task cannot raise it from the command line.
 */
public enum SecurityClass {

    /** No egress beyond what the base image needs. The agent never reaches an upstream. */
    OFFLINE,

    /** Curated egress sets only. Pushes go to a local mirror, never to the upstream. */
    GUARDED,

    /** Curated egress sets, and the git gate may push to the configured upstream. */
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
