package org.fuin.sokar.clearance;

/**
 * Asks the operator about a blocked connection.
 * <p>
 * An interface because there is more than one way to ask, and because the decision logic has to be
 * testable without a desktop session: notifications need a running session bus, which CI does not
 * have.
 */
@FunctionalInterface
public interface ClearancePrompt {

    /**
     * Asks about one destination and waits for an answer.
     *
     * @param request What to ask.
     * @return The operator's decision, or {@link Verdict#TIMEOUT}.
     */
    Verdict ask(ClearanceRequest request);
}
