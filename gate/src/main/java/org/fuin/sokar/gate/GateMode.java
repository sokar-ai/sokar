package org.fuin.sokar.gate;

import org.fuin.sokar.core.project.SecurityClass;

/**
 * What the gate is allowed to do with what an agent pushed.
 * <p>
 * {@link #OFFLINE} forwards nothing. {@link #GATEKEEPING} forwards what a person approved; {@link #ONLINE} also has its
 * gate pass a task's push on at once, through {@link GitGate#passOn}, which the task's gate server calls while the push
 * runs. In both, only the host holds the key the upstream takes.
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
     * Forwarding is permitted, as in {@link #GATEKEEPING}, and a task's push is passed on at once: its gate gives the
     * task's branch to the upstream while the agent's push runs, so nothing waits for review.
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
