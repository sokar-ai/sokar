package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.gate.GateMode;
import org.fuin.sokar.gate.GitGate;
import org.jspecify.annotations.Nullable;

/**
 * Builds a gate for a project file, so the gate commands do not each repeat it.
 */
final class GateSupport {

    private GateSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    static Project project(Path projectFile) {
        return ProjectReader.read(projectFile);
    }

    static Path mirror(Project project) {
        return new SokarPaths(XdgPaths.current(), Path.of("")).xdg().data()
                .resolve("mirrors").resolve(project.name() + ".git");
    }

    static GitGate gate(Project project, @Nullable String upstream) {
        return gate(project, upstream, null);
    }

    /**
     * Builds a gate, resolving where it forwards to and what it is first seeded from.
     *
     * @param project The project.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     * @param seed Repository to seed an empty mirror from, or {@code null}.
     * @return Gate.
     */
    static GitGate gate(Project project, @Nullable String upstream, @Nullable String seed) {
        final String forwardTo = upstream != null ? upstream : project.upstream();
        // The mode follows the project's security class, so an offline project cannot be talked
        // into forwarding by a command-line flag.
        return new GitGate(new ProcessCommandRunner(), mirror(project),
                GateMode.of(project.securityClass()), forwardTo,
                forwardTo != null ? forwardTo : seed);
    }
}
