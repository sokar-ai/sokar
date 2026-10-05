package org.fuin.sokar.vault;

/**
 * A message on the ssh-agent socket could not be understood.
 */
public class SshProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message What is wrong.
     */
    public SshProtocolException(String message) {
        super(message);
    }
}
