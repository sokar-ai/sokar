package org.fuin.sokar.app;

import java.io.PrintWriter;
import org.fuin.sokar.core.project.Project;

/**
 * Puts what an agent's own package ships into the image build context.
 * <p>
 * Split out of {@code TaskRunCommand} alongside {@link EgressReport}: this is the part that is
 * pure filesystem work, and none of it needs to know what a task is.
 * <p>
 * An earlier run's staged copy is reused rather than unpacked again - unpacking hundreds of
 * megabytes on every task would be the slowest thing a run does - and freshness is decided by a
 * marker beside the staged copy rather than by timestamps on the tree, because a package installs
 * with whatever mtimes it was built with.
 */
final class AgentStaging {

    private AgentStaging() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Copies what the agent ships into the build context and returns the lines that install it.
     * <p>
     * A tool that is a tree of files rather than one binary has no URL to pin, so its own package
     * carries it and the image build fetches nothing at all. That is why the copy happens here:
     * the container runtime can only see what is inside the build context.
     *
     * @param agent The agent.
     * @param project The project being built.
     * @param out Where progress is reported.
     * @return Build lines, empty when the agent ships nothing.
     */
    static java.util.List<String> stage(org.fuin.sokar.agent.api.InstalledAgent agent,
            Project project, SokarPaths paths, org.fuin.sokar.core.process.CommandRunner runner,
            PrintWriter out) {

        return stage(agent.name(), agent.executable(), agent.definition().packaged(), project, paths,
                runner, out);
    }

    /**
     * Copies what an agent ships into the build context, given what is known about it.
     *
     * @param name The agent's name, for messages.
     * @param executable The binary it was found at; a relative source is read beside it.
     * @param trees What its definition says it ships.
     * @param project The project being built.
     * @param paths Where the build context is.
     * @param runner Runs the unpacking.
     * @param out Where progress is reported.
     * @return Build lines, empty when the agent ships nothing.
     * @throws org.fuin.sokar.agent.api.AgentException If a declared tree is missing or cannot be staged.
     */
    static java.util.List<String> stage(String name, java.nio.file.Path executable,
            java.util.List<org.fuin.sokar.agent.api.PackagedTree> trees, Project project, SokarPaths paths,
            org.fuin.sokar.core.process.CommandRunner runner, PrintWriter out) {

        final java.util.List<String> lines = new java.util.ArrayList<>();
        for (final var tree : trees) {
            final java.nio.file.Path source = source(executable, tree.source());
            if (!java.nio.file.Files.exists(source)) {
                // Refused, not reported: an image built without the tree starts a task with no tool
                // in it, and the one line saying so was easy to miss among the rest.
                throw new org.fuin.sokar.agent.api.AgentException(name + " ships " + tree.target()
                        + " from " + source + ", and nothing is there - reinstall the agent");
            }
            final java.nio.file.Path staged = paths.buildContext(project.name())
                    .resolve(tree.stagingName());
            try {
                if (upToDate(source, staged)) {
                    // Staged by an earlier run of the same package. Unpacking hundreds of
                    // megabytes again on every task would be the slowest thing a task run does.
                    out.println("packaged  " + tree.target() + ", already staged");
                } else if (tree.archive()) {
                    unpack(runner, source, staged);
                    markStaged(source, staged);
                } else {
                    copyTree(source, staged);
                    markStaged(source, staged);
                }
            } catch (java.io.IOException | RuntimeException ex) {
                throw new org.fuin.sokar.agent.api.AgentException("could not stage " + source + " for "
                        + name + ": " + CliErrors.reason(ex), ex);
            }
            lines.add("COPY " + tree.stagingName() + " " + tree.target());
            out.println("packaged  " + tree.target() + " from this agent's own package");
        }
        return java.util.List.copyOf(lines);
    }

    /**
     * Finds what an agent ships, from where it says.
     * <p>
     * A relative path is read beside the agent's binary, so a copy of the agent in one account's
     * directory ships its own tree. It used to resolve against whatever directory {@code sokar} was
     * started in, which the CLI and the daemon do not share - and which is nothing to do with the agent.
     *
     * @param executable The agent's binary.
     * @param declared The path its definition gives.
     * @return The path to read.
     */
    static java.nio.file.Path source(java.nio.file.Path executable, String declared) {
        final java.nio.file.Path path = java.nio.file.Path.of(declared);
        return path.isAbsolute() ? path : executable.toAbsolutePath().resolveSibling(path);
    }

    /** Records which source a staged directory came from, so it is not unpacked twice. */
    static java.nio.file.Path marker(java.nio.file.Path staged) {
        return staged.resolveSibling(staged.getFileName() + ".from");
    }

    static String stamp(java.nio.file.Path source) throws java.io.IOException {
        return source + " " + java.nio.file.Files.size(source) + " "
                + java.nio.file.Files.getLastModifiedTime(source).toMillis();
    }

    static boolean upToDate(java.nio.file.Path source, java.nio.file.Path staged) {
        try {
            return java.nio.file.Files.isDirectory(staged)
                    && java.nio.file.Files.exists(marker(staged))
                    && java.nio.file.Files.readString(marker(staged)).equals(stamp(source));
        } catch (java.io.IOException ex) {
            return false;
        }
    }

    static void markStaged(java.nio.file.Path source, java.nio.file.Path staged)
            throws java.io.IOException {
        java.nio.file.Files.writeString(marker(staged), stamp(source));
    }

    /**
     * Unpacks an archive into the build context.
     *
     * @param runner Runs the tar that unpacks it.
     * @param source Archive to unpack.
     * @param target Directory to unpack into, replacing whatever was there.
     * @throws IOException If it cannot be unpacked.
     */
    static void unpack(org.fuin.sokar.core.process.CommandRunner runner,
            java.nio.file.Path source, java.nio.file.Path target) throws java.io.IOException {

        deleteTree(target);
        java.nio.file.Files.createDirectories(target);
        final var result = runner.run(org.fuin.sokar.core.process.Command.of(
                java.util.List.of("tar", "-xzf", source.toString(), "-C", target.toString())));
        if (!result.successful()) {
            throw new java.io.IOException("tar failed: " + result.standardError().strip());
        }
    }

    static void deleteTree(java.nio.file.Path directory) throws java.io.IOException {
        if (!java.nio.file.Files.exists(directory)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(directory)) {
            for (final java.nio.file.Path path
                    : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Copies a directory, replacing whatever was there.
     *
     * @param source Directory to copy.
     * @param target Where to put it.
     * @throws IOException If it cannot be copied.
     */
    static void copyTree(java.nio.file.Path source, java.nio.file.Path target)
            throws java.io.IOException {

        deleteTree(target);
        try (var walk = java.nio.file.Files.walk(source)) {
            for (final java.nio.file.Path path : walk.toList()) {
                final java.nio.file.Path destination = target.resolve(source.relativize(path));
                if (java.nio.file.Files.isDirectory(path)) {
                    java.nio.file.Files.createDirectories(destination);
                } else {
                    java.nio.file.Files.createDirectories(destination.getParent());
                    java.nio.file.Files.copy(path, destination,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
