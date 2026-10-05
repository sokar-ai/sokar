package org.fuin.sokar.shield;

/**
 * Thrown when a curated egress set cannot be read, or a project names one that does not exist.
 */
public class EgressSetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with a message.
     *
     * @param message What went wrong.
     */
    public EgressSetException(String message) {
        super(message);
    }

    /**
     * Constructor with a message and a cause.
     *
     * @param message What went wrong.
     * @param cause Underlying failure.
     */
    public EgressSetException(String message, Throwable cause) {
        super(message, cause);
    }
}
