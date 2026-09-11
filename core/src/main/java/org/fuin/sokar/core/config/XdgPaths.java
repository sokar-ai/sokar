package org.fuin.sokar.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The XDG base directories Sokar uses.
 * <p>
 * Resolved from the environment on construction rather than read on every access, so that a
 * process cannot see the layout change underneath it. Nothing here creates directories: a command
 * that needs one creates it, and a command that only reads must not have the side effect.
 *
 * @param config Where {@code config.yml} lives, {@code $XDG_CONFIG_HOME/sokar}.
 * @param data Installed hooks, images and other durable files, {@code $XDG_DATA_HOME/sokar}.
 * @param state Logs and audit trails, {@code $XDG_STATE_HOME/sokar}.
 * @param runtime Sockets, {@code $XDG_RUNTIME_DIR/sokar}.
 */
public record XdgPaths(Path config, Path data, Path state, Path runtime, Path home) {

    private static final String APPLICATION = "sokar";

    /**
     * Resolves the paths from the current environment.
     *
     * @return Paths for this user.
     */
    public static XdgPaths current() {
        return of(System::getenv, Path.of(System.getProperty("user.home")));
    }

    /**
     * Resolves the paths from a given environment. Exists so the resolution rules can be tested
     * without touching the real environment.
     *
     * @param env Environment lookup.
     * @param home The user's home directory.
     * @return Resolved paths.
     */
    public static XdgPaths of(EnvironmentLookup env, Path home) {
        return new XdgPaths(
                base(env, "XDG_CONFIG_HOME", home.resolve(".config")),
                base(env, "XDG_DATA_HOME", home.resolve(".local/share")),
                base(env, "XDG_STATE_HOME", home.resolve(".local/state")),
                base(env, "XDG_RUNTIME_DIR", Path.of("/run/user").resolve(String.valueOf(uid()))),
                // Kept rather than only consulted. One thing Sokar makes is for a person to open
                // in their own editor, and none of the four directories above is somewhere a
                // person or a file dialog looks.
                home);
    }

    private static Path base(EnvironmentLookup env, String variable, Path fallback) {
        // The specification says a relative value must be ignored, not resolved against the
        // current directory - a container runtime is the last place to want that ambiguity.
        return Optional.ofNullable(env.get(variable))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .filter(Path::isAbsolute)
                .orElse(fallback)
                .resolve(APPLICATION);
    }

    private static long uid() {
        // Read from /proc rather than through com.sun.security.auth.module.UnixSystem, which needs
        // reflection metadata in a native image, or through FFM, which this package has no other
        // reason to touch. Only used when XDG_RUNTIME_DIR is unset.
        try {
            for (final String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("Uid:")) {
                    return Long.parseLong(line.split("\\s+")[1]);
                }
            }
            throw new IllegalStateException("No 'Uid:' line in /proc/self/status");
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot determine the current user id", ex);
        }
    }

    /**
     * Reads a single environment variable.
     */
    @FunctionalInterface
    public interface EnvironmentLookup {

        /**
         * Returns the value of a variable.
         *
         * @param name Variable name.
         * @return Value, or {@code null} if unset.
         */
        @Nullable
        String get(String name);
    }
}
