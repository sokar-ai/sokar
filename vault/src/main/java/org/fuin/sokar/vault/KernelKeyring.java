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
 * disappears when the account's last session ends. Nothing is written to disk, and nothing
 * survives a reboot.
 * <p>
 * <strong>It is the USER keyring, which is why a daemon finds it.</strong> Measured on
 * 2026-09-19: a passphrase cached by one ssh session is readable by a later ssh session and by a
 * systemd <em>user</em> unit - which is what the daemon is. An interface that opens a vault by
 * running the unlock in a terminal it then closes has therefore opened it for the daemon too. The
 * command used to say "for this session", which invited exactly the opposite conclusion.
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

    /**
     * The errno values that mean the key is not there to be had.
     * <p>
     * Absence, expiry and revocation are the same answer to an operator: there is nothing cached
     * and nothing to do about it. Every other failure means the search could not establish
     * anything, and reporting that as absence is the tool making a claim about a secret that it
     * has not checked. {@code --for} makes expiry ordinary rather than exotic - it is what the
     * recommended option produces half an hour later.
     */
    private static final int ENOKEY = 126;

    /** A key whose timeout has passed; a retry cannot change the verdict. */
    private static final int EKEYEXPIRED = 127;

    /** A key whose keyring was revoked, which is what {@code pam_keyinit} does at logout. */
    private static final int EKEYREVOKED = 128;

    /** Special serial for the calling session's keyring. */
    static final int KEY_SPEC_SESSION_KEYRING = -3;

    /**
     * Where the passphrase is looked for, in order.
     * <p>
     * <strong>The user keyring first, because that is where it was put.</strong> The session
     * keyring finds it only through a link, and {@code pam_keyinit} revokes a session keyring when
     * its login ends - so a process that outlives its login, such as a {@code vault serve} for a
     * long task, was searching a revoked keyring while the key sat untouched in {@code @u}.
     */
    private static final int[] SEARCHED = { KEY_SPEC_USER_KEYRING, KEY_SPEC_SESSION_KEYRING };

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
        store(passphrase, null);
    }

    /**
     * Stores a passphrase in the keyring, replacing any previous one, and optionally bounds how
     * long the kernel keeps it.
     * <p>
     * <strong>The kernel discards it, not Sokar.</strong> Nothing has to remember to, no timer
     * runs, and a process that dies leaves nothing behind that outlives its welcome. Without a
     * bound the passphrase lives until it is dropped or until the user's last session ends, which
     * is one behaviour and no choice - the bound is the choice.
     *
     * @param passphrase What to store.
     * @param timeout How long the kernel should keep it, or {@code null} for no bound.
     * @throws VaultException If the keyring is unavailable or the kernel refuses.
     */
    public void store(char[] passphrase, java.time.@org.jspecify.annotations.Nullable
            Duration timeout) {

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

            if (timeout != null) {
                // Set last, on a key that is already stored and already restricted: a bound on a
                // key that failed to be locked down would be the wrong thing to get right.
                final MethodHandle setTimeout = handle("keyctl_set_timeout",
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
                final long bounded = (long) setTimeout.invokeExact(errno, serial,
                        (int) Math.max(1L, timeout.toSeconds()));
                if (bounded == -1L) {
                    throw new VaultException("Cannot bound how long the passphrase is kept"
                            + " (errno=" + errorNumber(errno) + ")");
                }
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

            long serial = -1L;
            for (final int keyring : SEARCHED) {
                serial = (long) search.invokeExact(errno, keyring,
                        arena.allocateFrom(TYPE), arena.allocateFrom(description), 0);
                if (serial != -1L) {
                    break;
                }
            }
            if (serial == -1L) {
                // Nothing cached is the ordinary case before the first unlock, and on this path
                // every other failure is harmless: the passphrase is asked for instead.
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
     * What became of the cached passphrase.
     * <p>
     * Three outcomes rather than two, because "nothing was cached" and "I could not tell" are
     * different things to say to somebody who has just asked for a secret to be dropped.
     */
    public enum Forgotten {

        /** It was cached and is not any more. */
        CLEARED,

        /** There was nothing cached: absent, expired or revoked, and a retry changes none of it. */
        NOTHING_CACHED,

        /** The keyring could not answer, so whether anything is still cached is not known. */
        UNKNOWN
    }

    /**
     * Removes the cached passphrase.
     * <p>
     * <strong>A failure is never reported as absence.</strong> The search returns {@code -1} for
     * any reason at all, and treating that as "nothing was cached" told an operator the passphrase
     * was gone while it was still in the keyring - a claim about a secret that had not been
     * checked. Only absence, expiry and revocation are answers; everything else is
     * {@link Forgotten#UNKNOWN}.
     *
     * @return What was established.
     */
    public Forgotten forget() {

        if (!available()) {
            return Forgotten.NOTHING_CACHED;
        }

        try (Arena arena = Arena.ofConfined()) {

            final MemorySegment errno = arena.allocate(ERRNO_LAYOUT);
            final MethodHandle search = handle("keyctl_search", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT);
            final MethodHandle unlink = handle("keyctl_unlink", ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

            long serial = -1L;
            int failure = 0;
            for (final int keyring : SEARCHED) {
                serial = (long) search.invokeExact(errno, keyring,
                        arena.allocateFrom(TYPE), arena.allocateFrom(description), 0);
                if (serial != -1L) {
                    break;
                }
                // The worst answer wins: one keyring saying "not here" does not settle it when
                // another said it could not tell.
                final int said = errorNumber(errno);
                failure = missing(said) && !missing(failure) && failure != 0 ? failure : said;
            }
            if (serial == -1L) {
                return missing(failure) ? Forgotten.NOTHING_CACHED : Forgotten.UNKNOWN;
            }
            if ((long) unlink.invokeExact(errno, (int) serial, KEY_SPEC_USER_KEYRING) == -1L) {
                // It was found and could not be removed, which is the one case that must never
                // read as cleared.
                return Forgotten.UNKNOWN;
            }
            return Forgotten.CLEARED;

        } catch (Throwable ex) {
            return Forgotten.UNKNOWN;
        }
    }

    /**
     * Tells whether an errno means the key is not there to be had.
     *
     * @param errno What the kernel said.
     * @return Whether that settles it as absence.
     */
    static boolean missing(int errno) {
        return errno == ENOKEY || errno == EKEYEXPIRED || errno == EKEYREVOKED;
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
