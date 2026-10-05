package org.fuin.sokar.core.process;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.List;

/**
 * Replaces the current process with another program.
 * <p>
 * Used to hand a terminal over to a shell inside a container. Starting a child and copying bytes
 * between the terminals would work too, but it leaves Sokar sitting between the operator and the
 * shell for the whole session: job control, window resizes and signals would all have to be
 * forwarded correctly, and each of them is a way for an interactive session to feel broken.
 * <p>
 * After a successful call there is no current process left to return to.
 */
public final class Exec {

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MemoryLayout ERRNO_LAYOUT = Linker.Option.captureStateLayout();

    private static final VarHandle ERRNO_HANDLE =
            ERRNO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    private static final MethodHandle EXECVP = execvp();

    private Exec() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static MethodHandle execvp() {
        final MemorySegment symbol = LINKER.defaultLookup().find("execvp")
                .orElseThrow(() -> new CommandException(Command.of("execvp"),
                        new IllegalStateException("Symbol 'execvp' not found in libc")));
        return LINKER.downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                Linker.Option.captureCallState("errno"));
    }

    /**
     * Replaces this process with the given program.
     *
     * @param arguments Program and its arguments.
     * @throws CommandException Always, if the call returns - a successful {@code execvp} does not.
     */
    public static void replaceCurrentProcess(List<String> arguments) {

        final Command command = Command.of(arguments);

        try (Arena arena = Arena.ofConfined()) {

            final MemorySegment errno = arena.allocate(ERRNO_LAYOUT);
            final MemorySegment file = arena.allocateFrom(arguments.getFirst());

            // argv is NULL-terminated, and argv[0] is the program name by convention.
            final MemorySegment argv =
                    arena.allocate(ValueLayout.ADDRESS, arguments.size() + 1L);
            for (int i = 0; i < arguments.size(); i++) {
                argv.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(arguments.get(i)));
            }
            argv.setAtIndex(ValueLayout.ADDRESS, arguments.size(), MemorySegment.NULL);

            final int result = (int) EXECVP.invokeExact(errno, file, argv);

            throw new CommandException(command, new IllegalStateException(
                    "execvp returned " + result + ", errno=" + (int) ERRNO_HANDLE.get(errno, 0L)));

        } catch (CommandException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new CommandException(command, ex);
        }
    }
}
