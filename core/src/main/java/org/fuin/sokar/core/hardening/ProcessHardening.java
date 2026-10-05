package org.fuin.sokar.core.hardening;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * The {@code prctl(2)} based process hardening measures.
 * <p>
 * Every measure is verified by reading the value back from the kernel. A zero return code is
 * <em>not</em> treated as proof: the point of hardening is that it is actually in effect, and a
 * call that silently applies to the wrong thread returns zero just the same.
 * <p>
 * <strong>{@code prctl} is per-thread.</strong> On a JVM the {@code main} method does not run on
 * the thread-group leader, so measures applied from {@code main} leave the process as a whole
 * unprotected while every return code says success. In a native-image binary {@code main} does run
 * on the leader. This is why the shipped binaries are native images rather than JVM programs, and
 * why {@link #appliesToWholeProcess()} exists - callers on a JVM must not believe the return codes.
 */
public final class ProcessHardening {

    /** Second argument of {@code prctl}: set the signal sent when the parent dies. */
    private static final int PR_SET_PDEATHSIG = 1;

    /** Second argument of {@code prctl}: read the parent-death signal. */
    private static final int PR_GET_PDEATHSIG = 2;

    /** Second argument of {@code prctl}: read the dumpable flag. */
    private static final int PR_GET_DUMPABLE = 3;

    /** Second argument of {@code prctl}: set the dumpable flag. */
    private static final int PR_SET_DUMPABLE = 4;

    /** Second argument of {@code prctl}: set the no-new-privileges flag. */
    private static final int PR_SET_NO_NEW_PRIVS = 38;

    /** Second argument of {@code prctl}: read the no-new-privileges flag. */
    private static final int PR_GET_NO_NEW_PRIVS = 39;

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MemoryLayout ERRNO_LAYOUT = Linker.Option.captureStateLayout();

    private static final VarHandle ERRNO_HANDLE =
            ERRNO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    /**
     * The single {@code prctl} descriptor used by this class. Adding a second distinct descriptor
     * anywhere means a new entry in {@code reachability-metadata.json} - see
     * the {@code ffm-check} profile.
     */
    private static final MethodHandle PRCTL = prctl();

    private ProcessHardening() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static MethodHandle prctl() {
        final SymbolLookup libc = LINKER.defaultLookup();
        final MemorySegment symbol = libc.find("prctl")
                .orElseThrow(() -> new HardeningException("Symbol 'prctl' not found in libc"));
        return LINKER.downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                Linker.Option.captureCallState("errno"));
    }

    private static int call(String what, int option, long arg2) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment errno = arena.allocate(ERRNO_LAYOUT);
            final int result = (int) PRCTL.invokeExact(errno, option, arg2, 0L, 0L, 0L);
            if (result < 0) {
                throw new HardeningException(what + " failed, errno=" + (int) ERRNO_HANDLE.get(errno, 0L));
            }
            return result;
        } catch (HardeningException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new HardeningException(what + " could not be called", ex);
        }
    }

    /**
     * Tells whether {@code prctl} calls made from the current thread cover the whole process.
     * <p>
     * True in a native image, where {@code main} runs on the thread-group leader. False on a JVM,
     * where it does not.
     *
     * @return {@code true} if the calling thread is the thread-group leader.
     */
    public static boolean appliesToWholeProcess() {
        return ProcessHandle.current().pid() == nativeThreadId();
    }

    private static long nativeThreadId() {
        // /proc/thread-self resolves to /proc/<pid>/task/<tid> for the calling thread.
        try {
            final String link = java.nio.file.Files
                    .readSymbolicLink(java.nio.file.Path.of("/proc/thread-self")).toString();
            return Long.parseLong(link.substring(link.lastIndexOf('/') + 1));
        } catch (Exception ex) {
            throw new HardeningException("Cannot determine the current native thread id", ex);
        }
    }

    /**
     * Disables core dumps and {@code ptrace} attachment, then verifies the flag by reading it back.
     *
     * @throws HardeningException If the flag is not zero afterwards.
     */
    public static void disableDumping() {
        call("PR_SET_DUMPABLE", PR_SET_DUMPABLE, 0L);
        if (dumpable() != 0) {
            throw new HardeningException("PR_SET_DUMPABLE returned success but the flag is still set");
        }
    }

    /**
     * Reads the dumpable flag.
     *
     * @return Zero when core dumps and {@code ptrace} are disabled.
     */
    public static int dumpable() {
        return call("PR_GET_DUMPABLE", PR_GET_DUMPABLE, 0L);
    }

    /**
     * Sets the dumpable flag. Only useful to restore the default after {@link #disableDumping()}.
     *
     * @param value New value, one to allow core dumps.
     */
    public static void setDumpable(int value) {
        call("PR_SET_DUMPABLE", PR_SET_DUMPABLE, value);
    }

    /**
     * Drops the ability to gain privileges through {@code setuid} binaries, then verifies it by
     * reading the flag back.
     * <p>
     * The flag cannot be cleared again for the lifetime of the process.
     *
     * @throws HardeningException If the flag is not set afterwards.
     */
    public static void dropPrivilegeEscalation() {
        call("PR_SET_NO_NEW_PRIVS", PR_SET_NO_NEW_PRIVS, 1L);
        if (!noNewPrivileges()) {
            throw new HardeningException("PR_SET_NO_NEW_PRIVS returned success but the flag is not set");
        }
    }

    /**
     * Reads the no-new-privileges flag.
     *
     * @return {@code true} if privilege escalation through {@code setuid} is disabled.
     */
    public static boolean noNewPrivileges() {
        return call("PR_GET_NO_NEW_PRIVS", PR_GET_NO_NEW_PRIVS, 0L) == 1;
    }

    /**
     * Requests that the given signal is sent to this process when its parent dies, then verifies it
     * by reading the signal back.
     *
     * @param signal Signal number, or zero to clear.
     * @throws HardeningException If a different signal is registered afterwards.
     */
    public static void dieWithParent(int signal) {
        call("PR_SET_PDEATHSIG", PR_SET_PDEATHSIG, signal);
        final int actual = parentDeathSignal();
        if (actual != signal) {
            throw new HardeningException(
                    "PR_SET_PDEATHSIG returned success but the signal is " + actual + ", not " + signal);
        }
    }

    /**
     * Reads the parent-death signal.
     *
     * @return Signal number, zero if none is registered.
     */
    public static int parentDeathSignal() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            call("PR_GET_PDEATHSIG", PR_GET_PDEATHSIG, out.address());
            return out.get(ValueLayout.JAVA_INT, 0);
        }
    }
}
