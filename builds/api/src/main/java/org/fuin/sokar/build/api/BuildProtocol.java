package org.fuin.sokar.build.api;

/**
 * The contract between Sokar and a build reader.
 * <p>
 * Sokar and the readers are packaged, versioned and installed separately, so a machine will one day hold a combination
 * nobody tested. The version is what makes that a clear refusal instead of a misread answer: a reader states which
 * protocol it speaks, and Sokar refuses one it does not know.
 */
public final class BuildProtocol {

    /** Varlink interface both sides implement. */
    public static final String INTERFACE = "org.fuin.sokar.Build1";

    /**
     * Version of this contract.
     * <p>
     * Raised when a change would make an older Sokar misread a newer reader, or the reverse. Adding an optional field
     * does not qualify; renaming or removing one does.
     */
    public static final int VERSION = 1;

    /** Returns the forge a reader reads and the protocol version it speaks. */
    public static final String DESCRIBE = INTERFACE + ".Describe";

    /** Returns the commit a branch points at on the forge. */
    public static final String HEAD = INTERFACE + ".Head";

    /** Returns what the build of one commit did. */
    public static final String LOOK = INTERFACE + ".Look";

    private BuildProtocol() {
        throw new UnsupportedOperationException("Constants");
    }

    /**
     * Returns a refusal's fully qualified error name.
     *
     * @param reason One of {@link BuildRefused.Reason}.
     * @return The name on the wire.
     */
    static String error(final BuildRefused.Reason reason) {
        return INTERFACE + "." + reason.wire();
    }
}
