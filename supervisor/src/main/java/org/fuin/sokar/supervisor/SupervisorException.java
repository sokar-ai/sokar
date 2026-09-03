package org.fuin.sokar.supervisor;

/** Something the per-container supervisor could not do. */
public class SupervisorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with a message.
     *
     * @param message What went wrong.
     */
    public SupervisorException(String message) {
        super(message);
    }

    /**
     * Constructor with a message and a cause.
     *
     * @param message What went wrong.
     * @param cause The underlying failure.
     */
    public SupervisorException(String message, Throwable cause) {
        super(message, cause);
    }
}
