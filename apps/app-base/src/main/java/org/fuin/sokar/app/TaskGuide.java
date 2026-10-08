package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.sokar.agent.api.AgentDefinition;

/**
 * Where a task's agent reads what Sokar gives it in its task - its handed-in files, the builds of its pushes, its
 * mailbox - and how it is told to.
 * <p>
 * One guide for all of Sokar, since an agent takes one file into its standing instructions. The host's half is a
 * directory of its own per task, mounted into the container when it is made; a task made before that has no such
 * mount and keeps its mailbox's guide.
 */
public final class TaskGuide {

    /** Where the directory appears inside the container. */
    public static final String MOUNT = "/run/sokar/guide";

    /** Where the agent reads the guide, inside the container. */
    public static final String GUIDE = MOUNT + "/README.md";

    private final Path directory;

    private final Mailbox mailbox;

    /**
     * Constructor. Creates nothing.
     *
     * @param paths The machine's roots.
     * @param container The task.
     */
    public TaskGuide(final SokarPaths paths, final String container) {
        this.directory = paths.tasks().guide(container);
        this.mailbox = new Mailbox(paths.messaging().mailbox(container));
    }

    /**
     * Returns the host's directory, the one that is mounted.
     *
     * @return Directory.
     */
    public Path directory() {
        return directory;
    }

    /**
     * Returns the host's side of {@link #GUIDE}.
     *
     * @return File.
     */
    public Path file() {
        return directory.resolve("README.md");
    }

    /**
     * Returns whether the task has the directory: made with it, so its container mounts it.
     *
     * @return {@code true} when it is a directory.
     */
    public boolean exists() {
        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Returns how an agent is started in this task: told where its guide is, when the agent declares a way to take it.
     * <p>
     * One rule for each way of starting it, attended, unattended and continued. A task made before the guide had a
     * directory of its own is given its mailbox's guide, as it was, and a task with neither gets nothing added.
     *
     * @param definition The agent.
     * @param command How it is started otherwise.
     * @return The command.
     */
    public List<String> instructing(final AgentDefinition definition, final List<String> command) {
        return Files.isRegularFile(file(), LinkOption.NOFOLLOW_LINKS) ? definition.instructed(command, GUIDE)
                : mailbox.instructing(definition, command);
    }

    /**
     * Deletes the directory. Called when the task is removed.
     *
     * @throws IOException Deleting failed.
     */
    public void delete() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(directory)) {
            for (final Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
