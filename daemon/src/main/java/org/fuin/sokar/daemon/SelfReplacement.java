package org.fuin.sokar.daemon;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;

/**
 * Notices that an update replaced this daemon's binary, and has its own service manager restart it.
 * <p>
 * The package starts nothing when it installs, on purpose: a daemon is each account's own. So after an update every
 * account went on running the old daemon - and the old unit definition - until somebody remembered to restart it.
 * An update renames the new binary over the old one, so the running process then sees its own executable as
 * deleted, with a new file at the same path. That is asked here every few seconds, without root and for every kind
 * of install alike.
 * <p>
 * <strong>Seen twice, then restarted.</strong> An update replaces the binary first and the unit file a moment later;
 * waiting for a second sight lets it finish. The restart reloads the definitions first, or it ran the new binary
 * under the old unit. It asks the account's own manager to restart the unit this daemon runs in, without waiting:
 * stopping is what ends this process. Tasks are not touched; they outlive the daemon as they always do.
 * <p>
 * A daemon started by hand has no unit to ask. It says once that it was replaced and goes on.
 */
final class SelfReplacement {

    /** How often to look. */
    static final Duration INTERVAL = Duration.ofSeconds(5);

    private static final String DELETED = " (deleted)";

    private final CommandRunner runner;

    private final PrintStream out;

    private final Supplier<String> executable;

    private final Predicate<Path> exists;

    private final Supplier<String> controlGroup;

    private boolean seen;

    private boolean told;

    /**
     * Creates the watch.
     *
     * @param runner Runs {@code systemctl}.
     * @param out Where it says what it does: the daemon's log.
     * @param executable Where {@code /proc/self/exe} points.
     * @param exists Whether a file is at a path.
     * @param controlGroup The text of {@code /proc/self/cgroup}.
     */
    SelfReplacement(CommandRunner runner, PrintStream out, Supplier<String> executable, Predicate<Path> exists,
            Supplier<String> controlGroup) {
        this.runner = runner;
        this.out = out;
        this.executable = executable;
        this.exists = exists;
        this.controlGroup = controlGroup;
    }

    /**
     * Creates the watch for this process.
     *
     * @param runner Runs {@code systemctl}.
     * @return The watch.
     */
    static SelfReplacement ofThisProcess(CommandRunner runner) {
        return new SelfReplacement(runner, System.out, () -> read(() -> Files.readSymbolicLink(
                Path.of("/proc/self/exe")).toString()), Files::isRegularFile,
                () -> read(() -> Files.readString(Path.of("/proc/self/cgroup"))));
    }

    /**
     * Says which file replaced the running binary, if one did.
     *
     * @param executable Where {@code /proc/self/exe} points.
     * @param exists Whether a file is at a path.
     * @return The new binary's path, or empty when the running one is still on disk, or was removed with nothing
     *         in its place.
     */
    static Optional<Path> replaced(String executable, Predicate<Path> exists) {
        if (!executable.endsWith(DELETED)) {
            return Optional.empty();
        }
        final Path path = Path.of(executable.substring(0, executable.length() - DELETED.length()));
        return exists.test(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * Says which unit this process runs in.
     *
     * @param controlGroup The text of {@code /proc/self/cgroup}.
     * @return The unit, or empty when it was not started as a service.
     */
    static Optional<String> unit(String controlGroup) {
        return controlGroup.lines().map(line -> line.substring(line.lastIndexOf('/') + 1).strip())
                .filter(name -> name.endsWith(".service") && !name.startsWith("user@")).findFirst();
    }

    /**
     * Looks once.
     *
     * @return {@code true} when a restart was asked for.
     */
    boolean check() {
        final Optional<Path> replacement = replaced(executable.get(), exists);
        if (replacement.isEmpty()) {
            seen = false;
            return false;
        }
        if (!seen) {
            seen = true;
            return false;
        }
        final Optional<String> unit = unit(controlGroup.get());
        if (unit.isEmpty()) {
            if (!told) {
                told = true;
                out.println("sokard: " + replacement.get() + " was replaced by an update; restart sokard to run it");
                out.flush();
            }
            return false;
        }
        out.println("sokard: " + replacement.get() + " was replaced by an update; restarting " + unit.get());
        out.flush();
        final CommandResult reloaded = runner.run(Command.of("systemctl", "--user", "daemon-reload"));
        if (!reloaded.successful()) {
            out.println("sokard: daemon-reload failed: " + reloaded.trimmedOutput());
        }
        final CommandResult restarted = runner.run(Command.of("systemctl", "--user", "restart", "--no-block",
                unit.get()));
        // Ended by a signal is the restart under way: stopping the unit stops everything in it, the call that
        // asked for it too.
        if (!restarted.successful() && restarted.exitCode() < 128) {
            // Said, and asked again at the next look: an update that left the manager unable to restart this
            // once may not the next time.
            out.println("sokard: restarting " + unit.get() + " failed (exit " + restarted.exitCode() + "): "
                    + restarted.trimmedOutput());
            out.flush();
            seen = false;
            return false;
        }
        return true;
    }

    /**
     * Looks every {@link #INTERVAL} until a restart was asked for, on a platform thread of its own.
     *
     * @return The thread.
     */
    Thread start() {
        return Thread.ofPlatform().daemon().name("sokard-self-replacement").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(INTERVAL);
                    if (check()) {
                        return;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ex) {
                    // A look that failed is not a reason to stop looking.
                    out.println("sokard: could not tell whether this binary was replaced: " + ex.getMessage());
                    out.flush();
                }
            }
        });
    }

    private interface Reading {
        String read() throws IOException;
    }

    private static String read(Reading reading) {
        try {
            return reading.read();
        } catch (IOException | UnsupportedOperationException ex) {
            return "";
        }
    }
}
