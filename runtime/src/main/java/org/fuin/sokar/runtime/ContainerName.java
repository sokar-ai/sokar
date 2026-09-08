package org.fuin.sokar.runtime;

import org.fuin.sokar.core.project.Project;

/**
 * Names of the objects Sokar creates in podman.
 * <p>
 * Every name carries the {@code sokar-} prefix so that an operator looking at {@code podman ps}
 * can tell what belongs to Sokar, and so that a cleanup can never match somebody else's container.
 */
public final class ContainerName {

    /** Prefix on every container Sokar creates. */
    public static final String PREFIX = "sokar-";

    /** Prefix on the throwaway containers an agent login runs in. */
    public static final String LOGIN_PREFIX = PREFIX + "login-";

    private ContainerName() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the container name for one task run.
     *
     * @param project The project.
     * @param task Task name.
     * @param runId Identifier unique within the task.
     * @return Container name.
     */
    public static String of(Project project, String task, String runId) {
        return PREFIX + project.name() + "-" + task + "-" + runId;
    }

    /**
     * Returns the container name for one agent login.
     * <p>
     * The millisecond is enough: a login is interactive, so two of them a millisecond apart would
     * need two people at the same terminal.
     *
     * @return Container name.
     */
    public static String login() {
        return LOGIN_PREFIX + System.currentTimeMillis();
    }

    /**
     * Tells whether a name belongs to Sokar.
     *
     * @param name Container name.
     * @return {@code true} if Sokar created it.
     */
    public static boolean isSokar(String name) {
        return name.startsWith(PREFIX);
    }

    /**
     * Tells whether a name is one of Sokar's login containers.
     * <p>
     * <strong>The prefix alone is not enough.</strong> {@code login} is a legal project name -
     * the name check allows any lowercase word - so a task in a project called that is named
     * {@code sokar-login-shell-25471} and would be taken for a login container: hidden from
     * {@code task list}, and refused by attach and stop. What separates them is that a login
     * carries only a timestamp where a task carries a task name and a run id, so the whole
     * remainder being digits is a shape no task name can take.
     *
     * @param name Container name.
     * @return {@code true} if it is a login container.
     */
    public static boolean isLogin(String name) {
        if (!name.startsWith(LOGIN_PREFIX)) {
            return false;
        }
        final String rest = name.substring(LOGIN_PREFIX.length());
        return !rest.isEmpty() && rest.chars().allMatch(Character::isDigit);
    }

    /**
     * Tells whether a name is a task.
     * <p>
     * <strong>Not every Sokar container is a task.</strong> {@code vault login} runs the agent's
     * own login in a throwaway container, which carries the prefix so that a cleanup can find it
     * and an operator reading {@code podman ps} can tell who made it - but it has no workspace, no
     * gate, no ruleset and no clearance, so everything a task offers would answer nonsense for it.
     * It was reported showing up in {@code sokar task list}, which is where this split comes from.
     *
     * @param name Container name.
     * @return {@code true} if the name is a task container.
     */
    public static boolean isTask(String name) {
        return isSokar(name) && !isLogin(name);
    }
}
