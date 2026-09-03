package org.fuin.sokar.hooks;

/**
 * Entry point of the {@code sokar-hook-reader} binary.
 * <p>
 * Starts the NFLOG reader at {@code createRuntime}. Soft-fail.
 * <p>
 * Compiled {@code --static --libc=musl}. This binary must make no FFM call - see
 * {@code NoForeignFunctionMemoryTest} and spike S5.
 */
public final class ReaderHook {

    private ReaderHook() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void main(final String[] args) {
        System.out.println("sokar-hook-reader (skeleton)");
    }
}
