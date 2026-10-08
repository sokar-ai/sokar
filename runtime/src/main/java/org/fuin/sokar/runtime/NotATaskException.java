package org.fuin.sokar.runtime;

/**
 * A container named like a task that Sokar holds no record of: not one it made, or one whose record is gone with it.
 * <p>
 * Its own type, so a caller answers "no such task" for it - what a client asking about a removed task is told -
 * rather than a failure: nothing went wrong, and nothing is done to the container.
 */
public final class NotATaskException extends ContainerException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param container The container's name.
     */
    public NotATaskException(final String container) {
        super(container + " is no task Sokar made: it has no record of it, so nothing is done to it");
    }
}
