package org.fuin.sokar.vault;

/**
 * The vault could not be read, written or unlocked.
 */
public class VaultException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message What failed.
     */
    public VaultException(String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message What failed.
     * @param cause Cause.
     */
    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
