package org.fuin.sokar.core.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Starts what a task leaves running in a systemd scope of its own, so that the task's lifetime is
 * not its starter's.
 * <p>
 * <strong>Why.</strong> Everything a process starts stays in that process's control group. The
 * daemon's unit sets no {@code KillMode}, so stopping it stops every process in its group - and a task
 * started through the daemon had its container's monitor, network, resolver and helpers there.
 * Measured: {@code systemctl --user stop sokard} left the task {@code Exited (143)}, although podman
 * had put the container itself in a scope of its own, because its monitor ({@code conmon}) was the
 * daemon's. The same task started inside a transient scope kept running with the daemon stopped.
 * <p>
 * <strong>How.</strong> {@code systemd-run --user --scope} registers a scope and then executes the
 * command in place - same process, same standard streams, same environment - so a caller that records
 * a pid or redirects output sees no difference. Everything the command starts in turn stays in that
 * scope, and a scope ends by itself once nothing in it runs, so no scope outlives the task it holds.
 * The CLI goes through here as well: how a task was started must not decide how long it lives.
 * <p>
 * Without a user manager to ask - a container, a machine without systemd - the command runs as it is,
 * which is what it did before any of this.
 */
public final class Scope {

    /** Where every task's scopes are, so a person can find them: {@code systemctl --user status sokar.slice}. */
    public static final String SLICE = "sokar.slice";

    private static final String SYSTEMD_RUN = "systemd-run";

    /**
     * Set to {@value #OFF}, what a task starts runs as it is. The unit tests set it, so that what they
     * assert does not depend on whether the machine running them has a user manager.
     */
    public static final String SWITCH = "SOKAR_TASK_SCOPES";

    /** The value of {@link #SWITCH} that turns scopes off. */
    public static final String OFF = "off";

    private Scope() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Wraps a command that leaves processes running, when this machine can give them a scope.
     *
     * @param description What the scope holds, as {@code systemctl} shows it.
     * @param command The command.
     * @return The command, prefixed to run in a scope of its own, or unchanged.
     */
    public static List<String> around(String description, List<String> command) {
        return around(description, command, System::getenv, Files::exists, Files::isExecutable);
    }

    /**
     * Wraps a command, asking the given environment whether a user manager is there.
     *
     * @param description What the scope holds.
     * @param command The command.
     * @param environment The environment to read {@code XDG_RUNTIME_DIR} and {@code PATH} from.
     * @param present Whether a path exists - asked of the user manager's socket.
     * @param executable Whether a path is an executable file - asked of {@code systemd-run}.
     * @return The command, prefixed or unchanged.
     */
    static List<String> around(String description, List<String> command,
            Function<String, @Nullable String> environment, Predicate<Path> present, Predicate<Path> executable) {
        if (OFF.equals(environment.apply(SWITCH))) {
            return command;
        }
        final String systemdRun = systemdRun(environment, executable);
        if (systemdRun == null || !manager(environment, present)) {
            return command;
        }
        final List<String> wrapped = new ArrayList<>(List.of(systemdRun, "--user", "--scope", "--quiet", "--collect",
                "--slice=" + SLICE, "--description=" + description, "--"));
        wrapped.addAll(command);
        return List.copyOf(wrapped);
    }

    private static boolean manager(Function<String, @Nullable String> environment, Predicate<Path> present) {
        final String runtime = environment.apply("XDG_RUNTIME_DIR");
        // The user manager's own socket: present exactly when there is a manager to register with.
        return runtime != null && !runtime.isBlank() && present.test(Path.of(runtime, "systemd", "private"));
    }

    private static @Nullable String systemdRun(Function<String, @Nullable String> environment,
            Predicate<Path> executable) {
        final String path = environment.apply("PATH");
        for (final String directory : (path == null ? "/usr/bin:/bin" : path).split(":")) {
            if (!directory.isBlank() && executable.test(Path.of(directory, SYSTEMD_RUN))) {
                return Path.of(directory, SYSTEMD_RUN).toString();
            }
        }
        return null;
    }
}
