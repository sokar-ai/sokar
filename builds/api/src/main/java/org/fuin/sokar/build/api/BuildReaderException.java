package org.fuin.sokar.build.api;

import java.io.Serial;

/**
 * A build reader could not be found, started or understood: a fault of the installation, not of the forge.
 */
public final class BuildReaderException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param message What went wrong, naming the reader.
     */
    public BuildReaderException(final String message) {
        super(message);
    }

    /**
     * Constructor with a cause.
     *
     * @param message What went wrong, naming the reader.
     * @param cause Why.
     */
    public BuildReaderException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
