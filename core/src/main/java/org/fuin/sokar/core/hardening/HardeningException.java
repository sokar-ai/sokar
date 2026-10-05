package org.fuin.sokar.core.hardening;

/**
 * A hardening measure could not be applied, or was applied but did not survive a read-back.
 */
public class HardeningException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message Description of what failed.
     */
    public HardeningException(String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message Description of what failed.
     * @param cause Cause.
     */
    public HardeningException(String message, Throwable cause) {
        super(message, cause);
    }
}
