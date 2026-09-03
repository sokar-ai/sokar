package org.fuin.sokar.app;

import java.util.List;
import java.util.function.Consumer;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.Exec;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.runtime.HookInstaller;

/**
 * Everything a command needs that touches the machine.
 * <p>
 * Exists so the commands can be tested. Without it a test of {@code sokar task run} builds an
 * image, starts a container and then replaces the test process with a shell - which is exactly
 * what happened before this was introduced.
 *
 * @param runner Runs external programs.
 * @param paths Where files go.
 * @param exec Replaces the current process; the real one never returns.
 */
public record SokarContext(CommandRunner runner, SokarPaths paths, Consumer<List<String>> exec) {

    /**
     * Returns the context used by the shipped binary.
     *
     * @return Context talking to the real machine.
     */
    public static SokarContext real() {
        return new SokarContext(new ProcessCommandRunner(), SokarPaths.current(),
                Exec::replaceCurrentProcess);
    }

    /**
     * Returns the hook installer for these paths.
     *
     * @return Installer.
     */
    public HookInstaller hooks() {
        return new HookInstaller(paths.hooksDirectory(), paths.binaryDirectory());
    }

    /**
     * Returns a task runner for these settings.
     *
     * @return Runner.
     */
    public TaskRunner tasks() {
        return new TaskRunner(runner, paths);
    }
}
