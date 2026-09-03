package org.fuin.sokar.gate;

import org.fuin.sokar.core.project.SecurityClass;

/**
 * What the gate is allowed to do with what an agent pushed.
 */
public enum GateMode {

    /**
     * Nothing ever reaches the upstream. The mirror is the end of the line, and the operator moves
     * work out of it by hand.
     */
    OFFLINE,

    /**
     * The operator approves each push before it is forwarded upstream. This is the mode the gate
     * exists for.
     */
    GATEKEEPING,

    /**
     * Approved pushes are forwarded to the configured upstream.
     */
    ONLINE;

    /**
     * Returns the mode implied by a project's security class.
     *
     * @param securityClass The class.
     * @return Matching mode.
     */
    public static GateMode of(SecurityClass securityClass) {
        return switch (securityClass) {
            case OFFLINE -> OFFLINE;
            case GUARDED -> GATEKEEPING;
            case ONLINE -> ONLINE;
        };
    }

    /**
     * Tells whether this mode can ever forward to an upstream.
     * <p>
     * Both {@link #GATEKEEPING} and {@link #ONLINE} can, but only after an explicit approval.
     * Nothing in the gate forwards without one; the difference between them is what the operator
     * is expected to do, not what the code permits.
     *
     * @return {@code true} if forwarding is possible at all.
     */
    public boolean canForward() {
        return this != OFFLINE;
    }
}
