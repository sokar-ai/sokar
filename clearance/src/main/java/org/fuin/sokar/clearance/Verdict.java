package org.fuin.sokar.clearance;

/**
 * What the operator decided about a blocked connection.
 */
public enum Verdict {

    /** Allow this destination from now on. */
    ALLOW,

    /** Leave it blocked. */
    DENY,

    /** No answer arrived in time. Treated as {@link #DENY}, but recorded differently. */
    TIMEOUT;

    /**
     * Tells whether the destination should be added to the allow set.
     * <p>
     * Only an explicit allow does. Silence is not consent: an operator who is away from the
     * machine must not open a container's egress by doing nothing.
     *
     * @return {@code true} only for {@link #ALLOW}.
     */
    public boolean allows() {
        return this == ALLOW;
    }
}
