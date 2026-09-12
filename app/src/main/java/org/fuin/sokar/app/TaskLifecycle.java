package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Stops what a task left running, from the pids it recorded.
 * <p>
 * Every helper a task starts writes its pid into the task's state directory, and the poststop hook
 * reads them when the container goes away. That covers a container that ran. It does not cover one
 * that never started - a refused ruleset, an image that will not build - because no hook fires for
 * a container that never existed, and the helpers started before it then outlive the run holding
 * their sockets.
 */
final class TaskLifecycle {

    private TaskLifecycle() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Tells whether the process a pid file names is still running.
     *
     * @param pidFile File holding one pid.
     * @return {@code true} if that process exists.
     */
    static boolean alive(Path pidFile) {
        return handle(pidFile).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * Returns handles on every helper the state directory records as running.
     * <p>
     * Taken before anything is stopped: the poststop hook deletes the pid files as it reaps them,
     * so afterwards there is nothing left to count and "how many were stopped" cannot be answered.
     *
     * @param stateDirectory The task's state directory.
     * @return Handles, in no particular order.
     */
    static java.util.List<ProcessHandle> running(Path stateDirectory) {
        if (!Files.isDirectory(stateDirectory)) {
            return java.util.List.of();
        }
        try (Stream<Path> files = Files.list(stateDirectory)) {
            return files.filter(TaskLifecycle::isPidFile)
                    .map(TaskLifecycle::handle)
                    .flatMap(java.util.Optional::stream)
                    .filter(ProcessHandle::isAlive)
                    .toList();
        } catch (IOException ex) {
            return java.util.List.of();
        }
    }

    /**
     * Stops every helper the state directory records, and forgets the pid files.
     *
     * @param stateDirectory The task's state directory.
     * @return How many running helpers were stopped.
     */
    static long stopHelpers(Path stateDirectory) {

        if (!Files.isDirectory(stateDirectory)) {
            return 0;
        }
        long stopped = 0;
        try (Stream<Path> files = Files.list(stateDirectory)) {
            for (final Path file : files.filter(TaskLifecycle::isPidFile).toList()) {
                if (handle(file).map(handle -> {
                    handle.destroy();
                    return true;
                }).orElse(false)) {
                    stopped++;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ex) {
                    // A pid file that cannot be removed names a process that is already stopped.
                }
            }
        } catch (IOException ex) {
            // Nothing useful can be done: this runs while handling a failure or on the way out.
        }
        return stopped;
    }

    private static boolean isPidFile(Path file) {
        return file.getFileName().toString().endsWith(".pid");
    }

    private static java.util.Optional<ProcessHandle> handle(Path pidFile) {
        // Verified, not merely parsed. A process id is reused, so the number in a file that
        // outlived its helper can belong to anything this user is running - and signalling on the
        // strength of it is how a tidy-up stops something nobody asked it to touch.
        return org.fuin.sokar.wire.HelperPid.verified(pidFile);
    }
}
