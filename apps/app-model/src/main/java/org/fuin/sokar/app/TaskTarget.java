package org.fuin.sokar.app;

import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.gate.LocalRepository;
import org.fuin.sokar.runtime.ContainerSummary;
import org.jspecify.annotations.Nullable;

/**
 * Which task a command means: the one it names - by its container's name or by its own - or, named by none, the one
 * started from the checkout the person stands in.
 * <p>
 * {@code sokar approve} found its task from the checkout, and the task commands wanted the container's name typed
 * out; one way now serves both.
 */
public final class TaskTarget {

    /** Exit: no task can be meant. */
    public static final int NONE = 66;

    /** Exit: several could be, and nobody can be asked. */
    public static final int SEVERAL = 64;

    /**
     * Asks the person which task they mean.
     */
    @FunctionalInterface
    public interface Asker {

        /**
         * Asks.
         *
         * @param question The question, without a line break.
         * @return The answer, or {@code null} when nobody answers.
         */
        @Nullable String ask(String question);
    }

    /**
     * What was found.
     *
     * @param container The task's container, or {@code null} when none is meant.
     * @param refusal Why none is, or "".
     * @param code The exit to end with when none is.
     */
    public record Found(@Nullable String container, String refusal, int code) {
    }

    private TaskTarget() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a task's container from either of its names: the container's, or the task's own when one task has it.
     *
     * @param context The machine.
     * @param given What the person typed.
     * @return The container's name, or {@code given} as it is when no task has it.
     */
    public static String spelled(final SokarContext context, final String given) {
        final List<ContainerSummary> tasks = context.podman().sokarTasks();
        if (tasks.stream().anyMatch(task -> task.name().equals(given))) {
            return given;
        }
        final List<String> own = tasks.stream().filter(task -> task.project() != null
                && task.name().equals("sokar-" + task.project() + "-" + given)).map(ContainerSummary::name).toList();
        return own.size() == 1 ? own.getFirst() : given;
    }

    /**
     * Returns the tasks started from the checkout a directory is in.
     *
     * @param context The machine.
     * @param directory Where the person stands.
     * @return Their containers, possibly none; {@code null} when the directory is in no checkout a repository is
     *         known by.
     */
    public static @Nullable List<String> fromCheckout(final SokarContext context, final Path directory) {
        final Path top = LocalRepository.topLevel(context.runner(), directory);
        if (top == null) {
            return null;
        }
        String project = null;
        String repository = null;
        final DefaultProject.Entry own = new DefaultProject(context).withUpstream(top.toString());
        if (own != null) {
            project = DefaultProject.NAME;
            repository = own.name();
        } else {
            final CommandResult remote = context.runner().run(Command.of("git", "-C", top.toString(), "remote",
                    "get-url", "origin"));
            final String origin = RepositoryAddress.withoutCredential(remote.successful()
                    ? remote.standardOutput().strip() : "");
            if (!origin.isEmpty()) {
                final WorkOrigin.Choice followed = WorkOrigin.followedNaming(context, origin);
                final DefaultProject.Entry earlier = followed == null
                        ? new DefaultProject(context).withUpstream(origin) : null;
                project = followed != null ? followed.project() : earlier != null ? DefaultProject.NAME : null;
                repository = followed != null ? followed.repository() : earlier != null ? earlier.name() : null;
            }
        }
        if (project == null) {
            return null;
        }
        final String inProject = project;
        final String ofRepository = repository;
        return context.podman().sokarTasks().stream().filter(task -> inProject.equals(task.project())
                && ofRepository != null && ofRepository.equals(task.repository())).map(ContainerSummary::name)
                .toList();
    }

    /**
     * Returns the task a command means.
     *
     * @param context The machine.
     * @param given What the person typed, or {@code null}.
     * @param directory Where the person stands.
     * @param asker How the person is asked when several could be meant, or {@code null} where nobody can be.
     * @return What was found.
     */
    public static Found resolve(final SokarContext context, final @Nullable String given, final Path directory,
            final @Nullable Asker asker) {
        if (given != null && !given.isBlank()) {
            return new Found(spelled(context, given.strip()), "", 0);
        }
        final List<String> started = fromCheckout(context, directory);
        if (started == null) {
            return new Found(null, "name a task; 'sokar task list' lists them, and in a checkout a task was started"
                    + " from no name is needed", SEVERAL);
        }
        final Path top = java.util.Objects.requireNonNull(LocalRepository.topLevel(context.runner(), directory));
        if (started.isEmpty()) {
            return new Found(null, "no task was started from " + top + "; 'sokar task start' starts one", NONE);
        }
        if (started.size() == 1) {
            return new Found(started.getFirst(), "", 0);
        }
        final String named = String.join(", ", started);
        final String answer = asker == null ? null
                : asker.ask("Several tasks were started from " + top + ": " + named + ". Which one?");
        if (answer == null || answer.isBlank()) {
            return new Found(null, "several tasks were started from " + top + ": " + named + "; name one", SEVERAL);
        }
        final String chosen = spelled(context, answer.strip());
        return started.contains(chosen) ? new Found(chosen, "", 0)
                : new Found(null, "'" + answer.strip() + "' is none of " + named, SEVERAL);
    }

    /**
     * Returns an asker on the person's terminal, or {@code null} when there is none to ask on.
     *
     * @param out Where the question is shown.
     * @return The asker, or {@code null}.
     */
    public static @Nullable Asker console(final java.io.PrintWriter out) {
        final java.io.Console console = System.console();
        if (console == null || !console.isTerminal()) {
            return null;
        }
        return question -> {
            out.print(question + " ");
            out.flush();
            return console.readLine();
        };
    }
}
