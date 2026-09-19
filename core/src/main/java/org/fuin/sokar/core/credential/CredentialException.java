package org.fuin.sokar.core.credential;

/**
 * What is wrong with the credential registry, said in one sentence.
 * <p>
 * Unchecked for the reason {@code ProjectException} is: every caller answers it the same way -
 * by telling the person what their file says and stopping - and a checked exception would only
 * spread that sentence over more code.
 */
public class CredentialException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with a message.
     *
     * @param message What is wrong.
     */
    public CredentialException(final String message) {
        super(message);
    }
}
