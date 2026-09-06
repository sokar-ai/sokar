package org.fuin.sokar.wire.varlink;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A varlink call failed, or a peer sent something that is not varlink.
 */
public class VarlinkException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    @Nullable
    private final transient String errorName;

    @Nullable
    private final transient Map<String, Object> parameters;

    /**
     * Constructor for a local failure.
     *
     * @param message What went wrong.
     */
    public VarlinkException(String message) {
        super(message);
        this.errorName = null;
        this.parameters = null;
    }

    /**
     * Constructor for a local failure with a cause.
     *
     * @param message What went wrong.
     * @param cause Cause.
     */
    public VarlinkException(String message, Throwable cause) {
        super(message, cause);
        this.errorName = null;
        this.parameters = null;
    }

    /**
     * Constructor for an error returned by a peer.
     *
     * @param errorName Fully qualified varlink error name.
     * @param parameters Error parameters.
     */
    public VarlinkException(String errorName, Map<String, Object> parameters) {
        super(errorName + " " + parameters);
        this.errorName = errorName;
        this.parameters = Map.copyOf(parameters);
    }

    /**
     * Returns the varlink error name, if a peer sent one.
     *
     * @return Error name, or {@code null}.
     */
    @Nullable
    public String getErrorName() {
        return errorName;
    }

    /**
     * Returns the error parameters, if a peer sent them.
     *
     * @return Parameters, or {@code null}.
     */
    @Nullable
    public Map<String, Object> getParameters() {
        return parameters;
    }
}
