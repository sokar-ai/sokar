package org.fuin.sokar.shield;

/**
 * A netlink operation failed.
 */
public class NetlinkException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int errorNumber;

    /**
     * Constructor with message and errno.
     *
     * @param message What failed.
     * @param errorNumber Value of {@code errno}, or zero.
     */
    public NetlinkException(String message, int errorNumber) {
        super(errorNumber == 0 ? message : message + " (errno=" + errorNumber + ")");
        this.errorNumber = errorNumber;
    }

    /**
     * Returns the captured {@code errno}.
     *
     * @return Error number, zero if none.
     */
    public int getErrorNumber() {
        return errorNumber;
    }
}
