package org.fuin.sokar.gate;

import org.fuin.sokar.core.project.SecurityClass;

/**
 * What the gate is allowed to do with what an agent pushed.
 * <p>
 * Only {@link #OFFLINE} changes how this gate behaves. {@link #GATEKEEPING} and {@link #ONLINE}
 * are indistinguishable here, because {@link GitGate#approve} is the only method that forwards
 * anything and both permit it. What separates those two lives in the task runner, not in this
 * package: an online project is given no gate at all.
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
     * Forwarding is permitted, exactly as in {@link #GATEKEEPING}.
     * <p>
     * A task never reaches this mode. The runner points an online project's agent straight at the
     * upstream and creates no gate, so nothing is ever pending for review. This is what the
     * {@code sokar gate} commands see when they are pointed at such a project by hand.
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
     * True for both {@link #GATEKEEPING} and {@link #ONLINE}, and in both the forwarding happens
     * only through {@link GitGate#approve}. Nothing else in the gate sends anything off the
     * machine.
     *
     * @return {@code true} if forwarding is possible at all.
     */
    public boolean canForward() {
        return this != OFFLINE;
    }
}
