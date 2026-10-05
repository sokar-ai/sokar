package org.fuin.sokar.core.project;

/**
 * A project definition could not be read, or is not usable as it stands.
 */
public class ProjectException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message Description of what is wrong.
     */
    public ProjectException(String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message Description of what is wrong.
     * @param cause Cause.
     */
    public ProjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
