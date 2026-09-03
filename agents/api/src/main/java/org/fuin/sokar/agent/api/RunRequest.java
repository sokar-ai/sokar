package org.fuin.sokar.agent.api;

import org.jspecify.annotations.Nullable;

/**
 * What a caller wants an agent to do.
 *
 * @param prompt The instruction.
 * @param model Model to use, or {@code null} for the agent's default.
 * @param maxTurns Turn limit, or {@code null} for none.
 * @param resumeSession Session to continue, or {@code null} to start fresh.
 * @param verbose Whether to ask for verbose output.
 * @param machineReadable Whether to ask for the agent's structured output format.
 */
public record RunRequest(String prompt, @Nullable String model, @Nullable Integer maxTurns,
        @Nullable String resumeSession, boolean verbose, boolean machineReadable) {

    /**
     * Creates a plain request.
     *
     * @param prompt The instruction.
     * @return New request.
     */
    public static RunRequest of(String prompt) {
        return new RunRequest(prompt, null, null, null, false, false);
    }
}
