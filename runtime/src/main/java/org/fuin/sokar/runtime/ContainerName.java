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
     * Returns the container name for one task.
     * <p>
     * <strong>One container per project and task, with nothing unique appended.</strong> It used
     * to carry the pid of the process that launched it, so two invocations never shared a
     * container and nothing could map a task to one - which is why starting a task that existed
     * built a second one beside it instead. An operator lost a task that way on 2026-09-11, and
     * three containers named for one task were the evidence.
     * <p>
     * The cost is deliberate: a task cannot run twice at once. Two runs of one task share the
     * project's mirror and its gate, so what is lost is a thing that only appeared to work.
     *
     * @param project The project.
     * @param task Task name.
     * @return Container name.
     */
    public static String of(Project project, String task) {
        return PREFIX + project.name() + "-" + task;
    }

    /**
     * The longest container name whose sockets still fit a unix socket path, for any uid.
     * <p>
     * A path holds 107 bytes, and a task's longest is
     * {@code /run/user/<uid>/sokar/<container>/ssh-agent.sock}: 10 + up to 10 + 7 + 65 + 15.
     */
    public static final int MAX_LENGTH = 65;

    /** A task name: the project names' alphabet, starting and ending with a letter or digit. */
    private static final java.util.regex.Pattern TASK =
            java.util.regex.Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    /**
     * Returns why a task name cannot be used, or empty when it can.
     * <p>
     * Asked before anything is built or written. podman refuses a container name outside
     * {@code [a-zA-Z0-9][a-zA-Z0-9_.-]*}, but only when the container is created - after the image
     * was built and the policy, resolver and sidecar were written under a directory named for a
     * task that never came to exist. The task is also a git ref, and a loose ref is a file, so
     * upper case would make {@code Foo} and {@code foo} one ref on a case-insensitive filesystem.
     * A name of only digits is refused because that is the shape that tells a login container
     * apart.
     *
     * @param project The project's name, or {@code ""} when it is not known: the length then goes
     *        unchecked.
     * @param task Task name.
     * @return A sentence saying what is wrong, or empty.
     */
    public static java.util.Optional<String> refusal(String project, String task) {
        if (!TASK.matcher(task).matches()) {
            final String suggested = task.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            return java.util.Optional.of("'" + task + "' is not a task name: use lowercase"
                    + " letters, digits and hyphens, starting and ending with a letter or digit"
                    + (TASK.matcher(suggested).matches() ? " - '" + suggested + "' would do" : ""));
        }
        if (task.chars().allMatch(Character::isDigit)) {
            return java.util.Optional.of("'" + task + "' is not a task name: it needs a letter,"
                    + " because a name of only digits is what marks a login container");
        }
        final String container = PREFIX + project + "-" + task;
        if (!project.isEmpty() && container.length() > MAX_LENGTH) {
            return java.util.Optional.of("'" + task + "' is too long for project '" + project
                    + "': '" + container + "' has " + container.length() + " characters, and a"
                    + " container name may have " + MAX_LENGTH + " so that its sockets fit a unix"
                    + " socket path");
        }
        return java.util.Optional.empty();
    }

    /**
     * Returns the task a name given for one means, taking a container name as the task it names.
     * <p>
     * {@code task list} shows container names and every other task verb takes one, so
     * {@code sokar-utils4j-shell} is what somebody types to start {@code shell} again - and it
     * used to become a second task, {@code sokar-utils4j-sokar-utils4j-shell}, with an empty
     * workspace, while the stopped one kept the work.
     *
     * @param project The project's name.
     * @param given What was given as the task.
     * @return The task name: the part after {@code sokar-<project>-}, or what was given.
     */
    public static String taskFrom(String project, String given) {
        final String prefix = PREFIX + project + "-";
        return given.startsWith(prefix) && given.length() > prefix.length()
                ? given.substring(prefix.length()) : given;
    }

    /**
     * Returns the task name inside a container name, when it holds one.
     * <p>
     * The interface is given this rather than deriving it: the rule relating the two belongs to
     * Sokar and has already changed once.
     *
     * @param project The project the container belongs to.
     * @param container Container name.
     * @return The task name, or {@code ""} when the name does not have this shape.
     */
    public static String taskIn(String project, String container) {
        final String prefix = PREFIX + project + "-";
        return container.startsWith(prefix) ? container.substring(prefix.length()) : "";
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
