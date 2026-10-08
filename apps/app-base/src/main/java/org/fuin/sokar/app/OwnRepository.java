package org.fuin.sokar.app;

import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The one rule about a project's own repository: when the file names other repositories, no work happens in the own
 * one.
 * <p>
 * The own repository holds {@code project.yml}, whose content decides what every task of the project may reach. A
 * task working there could change that for every other task, and a person who named no repository would have worked
 * there by default. Kept in one place because the terminal, the daemon and the gate commands must answer alike.
 */
final class OwnRepository {

    private OwnRepository() {
    }

    /**
     * Tells whether work in the repository of this name is refused.
     *
     * @param project The project.
     * @param repository As a person named it.
     * @return {@code true} for the project's own repository of a project that names others.
     */
    static boolean refused(Project project, @Nullable String repository) {
        return repository != null && !project.repositories().isEmpty() && repository.equals(project.name());
    }

    /**
     * Returns the sentence a refusal says.
     *
     * @param project The project.
     * @return What happened and where work goes instead.
     */
    /**
     * Names the repositories work can start in, for a refusal to offer: never the project's own, where no task works.
     * <p>
     * Offering every name, a start in an empty {@code default} said "It has: default" while {@code project default
     * list} said it had no repository yet.
     *
     * @param project The project.
     * @return The names, or how to add one when there is none.
     */
    static String worksIn(Project project) {
        // The default project's name, which this layer below the model cannot ask for by its constant. Its own
        // repository is never a place to work, even while it names no other.
        final boolean isDefault = "default".equals(project.name());
        final java.util.List<String> names = project.workRepositoryNames().stream()
                .filter(name -> !refused(project, name) && !(isDefault && name.equals(project.name()))).toList();
        if (!names.isEmpty()) {
            return String.join(", ", names);
        }
        return "none yet - " + (isDefault
                ? "add one with 'sokar project default add ADDRESS', or start in a checkout of it"
                : "its project file names no repository to work in");
    }

    static String refusal(Project project) {
        return "'" + project.name() + "' is the project's own repository, where its project.yml lives, and no task"
                + " works there. '" + project.name() + "' works in: "
                + String.join(", ", project.workRepositoryNames());
    }

    /**
     * Returns the repository a command about work means when neither a repository nor a task's record names one.
     * <p>
     * The project's own, when that is where it works. Never picked among named repositories, even one: a project that
     * grows a second would otherwise change what the same command does without a word.
     *
     * @param project The project.
     * @return The project's own repository.
     * @throws org.fuin.sokar.core.project.ProjectException When the project names repositories; the message names them.
     */
    static Repository unnamed(Project project) {
        if (project.repositories().isEmpty()) {
            return project.ownRepository();
        }
        throw new org.fuin.sokar.core.project.ProjectException("say which repository with -r. '" + project.name()
                + "' works in: " + String.join(", ", project.workRepositoryNames()));
    }
}
