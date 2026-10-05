package org.fuin.sokar.clearance;

/**
 * The operator could not be asked.
 */
public class ClearanceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message What failed.
     */
    public ClearanceException(String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message What failed.
     * @param cause Cause.
     */
    public ClearanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
