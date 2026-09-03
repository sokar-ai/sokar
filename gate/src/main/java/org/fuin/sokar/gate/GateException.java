package org.fuin.sokar.gate;

/**
 * A gate operation failed.
 */
public class GateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message What failed.
     */
    public GateException(String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message What failed.
     * @param cause Cause.
     */
    public GateException(String message, Throwable cause) {
        super(message, cause);
    }
}
