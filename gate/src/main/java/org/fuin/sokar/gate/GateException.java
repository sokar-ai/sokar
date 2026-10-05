package org.fuin.sokar.gate;

/**
 * A gate operation failed.
 */
public class GateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message What failed.
     */
    public GateException(String message) {
        super(message);
    }

    /**
     * Constructor with message and cause.
     *
     * @param message What failed.
     * @param cause Cause.
     */
    public GateException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * A push moved after a person reviewed it, so nothing was forwarded: what goes is what was read.
     */
    public static final class MovedSinceReview extends GateException {

        private static final long serialVersionUID = 1L;

        /** The commit the person reviewed. */
        private final String reviewed;

        /** The commit the push holds now. */
        private final String now;

        /**
         * Constructor.
         *
         * @param name The pending push.
         * @param reviewed The commit the person reviewed.
         * @param now The commit it holds now.
         */
        public MovedSinceReview(String name, String reviewed, String now) {
            super("'" + name + "' moved since it was reviewed: " + reviewed + " was read, it holds " + now
                    + " now. Review it again; nothing was forwarded");
            this.reviewed = reviewed;
            this.now = now;
        }

        /** @return The commit the person reviewed. */
        public String reviewed() {
            return reviewed;
        }

        /** @return The commit the push holds now. */
        public String now() {
            return now;
        }
    }
}
