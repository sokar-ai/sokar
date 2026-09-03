package org.fuin.sokar.agent.api;

/**
 * An agent definition could not be read, or is not usable as it stands.
 */
public class AgentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message What is wrong.
     */
    public AgentException(String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message What is wrong.
     * @param cause Cause.
     */
    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
