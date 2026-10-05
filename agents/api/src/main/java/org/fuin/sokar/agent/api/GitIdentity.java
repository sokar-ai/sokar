package org.fuin.sokar.agent.api;

/**
 * The author an agent's commits are attributed to.
 * <p>
 * Set explicitly rather than inherited from the operator, so that a commit made by an agent is
 * distinguishable from one made by a person in the history itself, not only in a review queue.
 *
 * @param name Author name.
 * @param email Author email.
 */
public record GitIdentity(String name, String email) {

    /**
     * Constructor with all data.
     *
     * @param name Author name.
     * @param email Author email.
     */
    public GitIdentity {
        if (name.isBlank() || email.isBlank()) {
            throw new AgentException("A git identity needs both a name and an email");
        }
    }
}
