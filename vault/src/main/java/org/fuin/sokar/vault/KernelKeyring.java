package org.fuin.sokar.vault;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The Linux kernel keyring, used as a volatile unlock cache.
 * <p>
 * The passphrase is held by the kernel rather than by any file, so it survives between
 * {@code sokar} invocations - an operator unlocks once per login instead of once per command - and
 * disappears when the session ends. Nothing is written to disk, and nothing survives a reboot.
 * <p>
 * The key is created in the <em>user</em> keyring and linked into the session keyring, which is
 * what makes it visible to later processes in the same session. Its permission mask is
 * {@code 0x3F2F0000}: all rights for the owner, none for anyone else.
 * <p>
 * Requires {@code libkeyutils}. Where it is absent every method reports unavailable rather than
 * failing, because a missing unlock cache is an inconvenience and not an error.
 */
public class KernelKeyring {

    /** Special serial for the calling user's keyring. */
    static final int KEY_SPEC_USER_KEYRING = -4;

    /** Special serial for the calling session's keyring. */
    static final int KEY_SPEC_SESSION_KEYRING = -3;

    /**
     * Owner may view, read, write, search, link and set attributes; nobody else may do anything.
     */
    static final int KEY_PERMISSIONS = 0x3F2F0000;

    /** Key type. A "user" key holds arbitrary bytes. */
    private static final String TYPE = "user";

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MemoryLayout ERRNO_LAYOUT = Linker.Option.captureStateLayout();

    private static final VarHandle ERRNO_HANDLE =
            ERRNO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    private static final Linker.Option CAPTURE = Linker.Option.captureCallState("errno");

    private static final SymbolLookup KEYUTILS = load();

    private static SymbolLookup load() {
        for (final String name : new String[] { "libkeyutils.so.1", "libkeyutils.so" }) {
            try {
                return SymbolLookup.libraryLookup(name, Arena.global());
            } catch (IllegalArgumentException | UnsatisfiedLinkError ex) {
                // Try the next name.
            }
        }
        return null;
    }

    private final String description;

    /**
     * Constructor.
     *
     * @param description Key description, which is how it is found again.
     */
    public KernelKeyring(String description) {
        this.description = description;
    }

    /**
     * Tells whether the kernel keyring can be used here.
     *
     * @return {@code true} if {@code libkeyutils} is present.
     */
    public static boolean available() {
        return KEYUTILS != null;
    }

    private static MethodHandle handle(String name, MemoryLayout returnType,
            MemoryLayout... arguments) {
        return LINKER.downcallHandle(
                KEYUTILS.find(name).orElseThrow(() ->
                        new VaultException("Symbol '" + name + "' not found in libkeyutils")),
                FunctionDescriptor.of(returnType, arguments), CAPTURE);
    }

    /**
     * Stores a passphrase in the keyring, replacing any previous one.
     *
     * @param passphrase What to store.
     * @throws VaultException If the keyring is unavailable or the kernel refuses.
     */
    public void store(char[] passphrase) {

        requireAvailable();
        final byte[] bytes = new String(passphrase).getBytes(StandardCharsets.UTF_8);

        try (Arena arena = Arena.ofConfined()) {

            final MemorySegment errno = arena.allocate(ERRNO_LAYOUT);
            final MethodHandle link = handle("keyctl_link", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
            final MethodHandle addKey = handle("add_key", ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
            final MethodHandle setPermissions = handle("keyctl_setperm", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

            // Link the user keyring into the session keyring first, or a key added to the user
            // keyring is not findable from a later process in the same session.
            final long linked = (long) link.invokeExact(errno,
                    KEY_SPEC_USER_KEYRING, KEY_SPEC_SESSION_KEYRING);
            if (linked == -1L) {
                throw new VaultException("Cannot link the user keyring into the session keyring"
                        + " (errno=" + errorNumber(errno) + ")");
            }

            final MemorySegment payload = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, payload, ValueLayout.JAVA_BYTE, 0, bytes.length);

            final int serial = (int) addKey.invokeExact(errno,
                    arena.allocateFrom(TYPE), arena.allocateFrom(description),
                    payload, (long) bytes.length, KEY_SPEC_USER_KEYRING);
            if (serial == -1) {
                throw new VaultException("Cannot store the passphrase in the keyring"
                        + " (errno=" + errorNumber(errno) + ")");
            }

            final long permissions = (long) setPermissions.invokeExact(errno, serial, KEY_PERMISSIONS);
            if (permissions == -1L) {
                throw new VaultException("Cannot restrict the keyring entry"
                        + " (errno=" + errorNumber(errno) + ")");
            }

        } catch (VaultException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new VaultException("The kernel keyring call failed", ex);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Reads the passphrase back, if one is cached.
     *
     * @return The passphrase, or empty when nothing is cached or the keyring is unavailable.
     */
    public Optional<char[]> read() {

        if (!available()) {
            return Optional.empty();
        }

        try (Arena arena = Arena.ofConfined()) {

            final MemorySegment errno = arena.allocate(ERRNO_LAYOUT);
            final MethodHandle search = handle("keyctl_search", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT);
            final MethodHandle read = handle("keyctl_read", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);

            final long serial = (long) search.invokeExact(errno, KEY_SPEC_SESSION_KEYRING,
                    arena.allocateFrom(TYPE), arena.allocateFrom(description), 0);
            if (serial == -1L) {
                // Nothing cached is the ordinary case before the first unlock.
                return Optional.empty();
            }

            final MemorySegment buffer = arena.allocate(4096);
            final long length = (long) read.invokeExact(errno, (int) serial, buffer, 4096L);
            if (length <= 0L || length > 4096L) {
                return Optional.empty();
            }

            final byte[] bytes = buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
            final char[] passphrase = new String(bytes, StandardCharsets.UTF_8).toCharArray();
            java.util.Arrays.fill(bytes, (byte) 0);
            return Optional.of(passphrase);

        } catch (Throwable ex) {
            return Optional.empty();
        }
    }

    /**
     * Removes the cached passphrase.
     *
     * @return {@code true} if something was removed.
     */
    public boolean forget() {

        if (!available()) {
            return false;
        }

        try (Arena arena = Arena.ofConfined()) {

            final MemorySegment errno = arena.allocate(ERRNO_LAYOUT);
            final MethodHandle search = handle("keyctl_search", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT);
            final MethodHandle unlink = handle("keyctl_unlink", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

            final long serial = (long) search.invokeExact(errno, KEY_SPEC_SESSION_KEYRING,
                    arena.allocateFrom(TYPE), arena.allocateFrom(description), 0);
            if (serial == -1L) {
                return false;
            }
            return (long) unlink.invokeExact(errno, (int) serial, KEY_SPEC_USER_KEYRING) != -1L;

        } catch (Throwable ex) {
            return false;
        }
    }

    private static void requireAvailable() {
        if (!available()) {
            throw new VaultException("libkeyutils is not available, so the kernel keyring"
                    + " cannot be used on this host");
        }
    }

    private static int errorNumber(MemorySegment errno) {
        return (int) ERRNO_HANDLE.get(errno, 0L);
    }

    /**
     * A passphrase source backed by the kernel keyring.
     *
     * @param description Key description.
     * @return Source that reads the cached passphrase.
     */
    public static PassphraseSource source(String description) {
        final KernelKeyring keyring = new KernelKeyring(description);
        return new PassphraseSource() {

            @Override
            public Optional<char[]> passphrase() {
                return keyring.read();
            }

            @Override
            public String name() {
                return "kernel-keyring";
            }
        };
    }
}
