package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.jspecify.annotations.Nullable;

/**
 * Where Sokar keeps the files of projects: what is followed, its clones, its signers and its records.
 * <p>
 * One class per area, beside {@link SokarPaths}, which keeps only the roots and what belongs to the machine as a
 * whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record ProjectPaths(SokarPaths paths) {

    /**
     * Returns where this account records the project repositories it follows.
     * <p>
     * A directory with one file per project, like the registry beside it and for the same reason:
     * two things can change at once, and a file each is a race that cannot happen.
     * <p>
     * <strong>Per account, not per machine.</strong> Two people on one machine follow different
     * projects, reconcile at different moments and open different vaults.
     *
     * @return The directory.
     */
    public Path followed() {
        return paths.xdg().state().resolve("follow");
    }

    /**
     * Returns where a followed project's repository is cloned.
     * <p>
     * Under the state directory because it is this machine's copy of a source of truth, not a
     * working copy: nothing edits it and it is rebuilt by fetching. An agent that works <em>in</em>
     * a project's repository gets its own checkout through the gate, like a task on any other
     * repository.
     *
     * @param project The project's name.
     * @return The clone's directory, which need not exist.
     */
    public Path followedClone(String project) {
        return followed().resolve(project + ".git");
    }

    /**
     * Returns the file naming the keys whose commits this machine will apply as configuration.
     * <p>
     * <strong>Pinned out of band, and never from the repository it authenticates.</strong> A trust
     * anchor that travels with what it checks is not an anchor. An operator writes this when the
     * machine is prepared, the same way a peer's key is pinned for messages, and it is in the
     * configuration directory rather than the data one because it is a decision rather than
     * something Sokar accumulated.
     * <p>
     * {@code allowed_signers} format, so it is the same file shape - and the same reader - as the
     * one that decides which peers may send a message.
     *
     * @return The file, which need not exist. A machine with none applies nothing.
     */
    public Path configurationSigners() {
        // The keys whose commits a followed project's configuration is taken from: people's, never a machine's.
        return paths.xdg().config().resolve("project-signers");
    }

    public Path projectRegistry() {
        return paths.xdg().data().resolve("projects");
    }

    public Path upstreamRecords() {
        return paths.xdg().data().resolve("upstream");
    }

    /**
     * Returns where a project's file goes when nobody says where.
     * <p>
     * <strong>A place rather than a prompt.</strong> An interface reaching a machine over a
     * forwarded socket cannot look at its filesystem, so asking a person for an absolute path there
     * asks them to guess - and an interface that guessed for them would be writing this layout down
     * a second time, where it could not be kept in step.
     * <p>
     * A directory per project, so that what belongs to it - an image snippet, anything a project
     * grows later - can sit beside it rather than somewhere else with a name that has to match.
     *
     * @param name The project's name.
     * @return The file, which need not exist.
     */
    public Path defaultProjectFile(String name) {
        return paths.xdg().config().resolve("projects").resolve(name).resolve("project.yml");
    }
}
