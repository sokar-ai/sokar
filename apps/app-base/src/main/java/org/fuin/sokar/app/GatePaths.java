package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.jspecify.annotations.Nullable;

/**
 * Where Sokar keeps the files of the gate: its review checkouts and its backups.
 * <p>
 * One class per area, beside {@link SokarPaths}, which keeps only the roots and what belongs to the machine as a
 * whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record GatePaths(SokarPaths paths) {

    /**
     * Returns the directory recording what backups have been taken.
     * <p>
     * A bundle goes wherever the operator names it, so this is the only thing that knows one was
     * ever taken. It records where, not what: the file it names can be moved or replaced
     * afterwards and nothing here would know.
     *
     * @return The directory, which may not exist yet.
     */
    public Path backupRecords() {
        return paths.xdg().data().resolve("backups");
    }

    /**
     * Returns where a readable copy of waiting work is put.
     * <p>
     * <strong>Visible, in the home directory, and not under the data directory.</strong> This is
     * the one thing Sokar makes for a person to open in their own editor rather than for itself
     * to read back, and {@code ~/.local/share} is where neither a file dialog nor a person looks.
     * The command exists so the safe way is also the convenient one; hiding its result would have
     * repaired the convenience in one place by breaking it in another.
     * <p>
     * It used to default beside the project file, which normally sits inside the operator's git
     * checkout - so reviewing an agent's work left an untracked directory in a repository Sokar
     * promises not to touch, committable by accident and cleaned up by nothing. Reported from a
     * test machine where one had sat for two days.
     *
     * @param project Project the work belongs to.
     * @param name The waiting ref's name.
     * @return The directory to check out into.
     */
    public Path reviewCheckout(String project, String name) {
        return paths.xdg().home().resolve("sokar").resolve("reviews").resolve(project + "-" + name);
    }
}
