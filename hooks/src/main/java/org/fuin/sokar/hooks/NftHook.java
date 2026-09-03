package org.fuin.sokar.hooks;

/**
 * Entry point of the {@code sokar-hook-nft} binary.
 * <p>
 * Loads the generated nftables ruleset inside the container network namespace at {@code createRuntime}. Fail-closed: a non-zero exit stops the container from starting.
 * <p>
 * Compiled {@code --static --libc=musl}. This binary must make no FFM call - see
 * {@code NoForeignFunctionMemoryTest} and spike S5.
 */
public final class NftHook {

    private NftHook() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void main(final String[] args) {
        System.out.println("sokar-hook-nft (skeleton)");
    }
}
