package org.fuin.sokar.hooks;

/**
 * Entry point of the {@code sokar-hook-supervisor} binary.
 * <p>
 * Starts the per-container supervisor at {@code createRuntime} and reaps it at {@code poststop}. Soft-fail.
 * <p>
 * Compiled {@code --static --libc=musl}. This binary must make no FFM call - see
 * {@code NoForeignFunctionMemoryTest} and spike S5.
 */
public final class SupervisorHook {

    private SupervisorHook() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void main(final String[] args) {
        System.out.println("sokar-hook-supervisor (skeleton)");
    }
}
