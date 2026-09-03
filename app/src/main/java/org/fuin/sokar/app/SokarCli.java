package org.fuin.sokar.app;

/**
 * Entry point of the {@code sokar} binary.
 * <p>
 * Placeholder for the picocli command tree. It exists so the native-image profile has a main
 * class to compile, and so the dynamic-link half of the artifact split is exercised by the build.
 */
public final class SokarCli {

    private SokarCli() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void main(final String[] args) {
        System.out.println("sokar (skeleton)");
    }
}
