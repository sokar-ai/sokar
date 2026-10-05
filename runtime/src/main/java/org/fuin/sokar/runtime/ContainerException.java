package org.fuin.sokar.runtime;

/**
 * A container or image operation could not be carried out.
 */
public class ContainerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message Description of what failed.
     */
    public ContainerException(String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message Description of what failed.
     * @param cause Cause.
     */
    public ContainerException(String message, Throwable cause) {
        super(message, cause);
    }
}
