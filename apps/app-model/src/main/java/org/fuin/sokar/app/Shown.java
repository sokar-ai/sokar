package org.fuin.sokar.app;

import java.util.List;

/**
 * What a refusal shows at once where it would name the command that shows it: the tasks there are, the projects
 * followed, why a followed project is not in force. The answer was known; making a person type the command to read it
 * was a step with no decision in it.
 */
public final class Shown {

    private Shown() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a list said in one line, or what is said when it is empty.
     *
     * @param what What the names are, as the line begins.
     * @param names The names.
     * @param none What is said when there are none.
     * @return One line.
     */
    static String names(final String what, final List<String> names, final String none) {
        return names.isEmpty() ? none : what + ": " + String.join(", ", names);
    }

    /**
     * Returns the tasks on this machine, said in one line.
     *
     * @param context The machine.
     * @return One line.
     */
    public static String tasks(final SokarContext context) {
        return names("the tasks here", new TaskInventory(context).tasks().stream().map(TaskInventory.Task::name)
                .toList(), "there is no task here");
    }

    /**
     * Returns the projects this account follows, said in one line.
     *
     * @param context The machine.
     * @return One line.
     */
    public static String followed(final SokarContext context) {
        try {
            return names("the projects followed here", new FollowedProjects(context.paths().projects().followed())
                    .all().stream().map(FollowedProjects.Followed::name).toList(), "this account follows no project");
        } catch (final java.io.IOException ex) {
            return "the projects followed here could not be read: " + ex.getMessage();
        }
    }

    /**
     * Returns why a followed project is not in force, from its record: what the last try came to and what it said.
     *
     * @param context The machine.
     * @param name The project.
     * @return One line.
     */
    public static String whyNotInForce(final SokarContext context, final String name) {
        try {
            return new FollowedProjects(context.paths().projects().followed()).all().stream()
                    .filter(followed -> followed.name().equals(name)).findFirst()
                    .map(followed -> "its last fetch came to " + followed.outcome().toLowerCase(java.util.Locale.ROOT)
                            + (followed.detail().isBlank() ? "" : ": " + followed.detail()))
                    .orElse("it has no record of a fetch");
        } catch (final java.io.IOException ex) {
            return "its record could not be read: " + ex.getMessage();
        }
    }
}
