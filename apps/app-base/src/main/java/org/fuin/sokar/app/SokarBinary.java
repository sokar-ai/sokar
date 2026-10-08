package org.fuin.sokar.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the {@code sokar} binary is, for the helpers a task starts.
 * <p>
 * A task's gate, credential broker, relay and clearance watcher are all {@code sokar} running with
 * different arguments, and until now each one asked {@code ProcessHandle.current()} for the path to
 * re-execute. That is right in the CLI and wrong everywhere else: measured, a task started through
 * the daemon tried to run {@code sokard gate serve}, which is a binary with no such command, so the
 * container came up with **no gate and no clearance watcher** and nothing said so - the task looked
 * like it had started.
 * <p>
 * The current process wins when it is itself {@code sokar}, which keeps the property that a local
 * build shadows a packaged one: a developer testing a build must get their own helpers, not the
 * installed ones. Otherwise the answer is the {@code sokar} beside whatever is running, and only
 * then the one on the path.
 */
final class SokarBinary {

    /** Name of the CLI, which is also what every helper is started as. */
    private static final String NAME = "sokar";

    private SokarBinary() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns what to run to start a helper.
     *
     * @return Path to the CLI, or its bare name when nothing better can be found.
     */
    static String path() {
        final Optional<String> self = ProcessHandle.current().info().command();
        if (self.isEmpty()) {
            return NAME;
        }
        final Path running = Path.of(self.get());
        if (NAME.equals(running.getFileName().toString())) {
            return running.toString();
        }
        final Path beside = running.toAbsolutePath().getParent() == null ? null
                : running.toAbsolutePath().getParent().resolve(NAME);
        if (beside != null && Files.isExecutable(beside)) {
            return beside.toString();
        }
        return NAME;
    }
}
