package org.fuin.sokar.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stops every running task and every helper any of them started.
 * <p>
 * Split out of {@link PanicCommand} so the daemon serves the same operation rather than a second
 * one. The reason this matters more here than elsewhere: an interface that shelled out to
 * {@code sokar panic} would be running a command, and this is the one command somebody reaches for
 * when they do not know what is wrong - the worst possible moment for two implementations to
 * differ about what "stop everything" means.
 * <p>
 * <strong>It stops; it never removes.</strong> The workspace, the logs and whatever never reached
 * the gate all survive, and {@code task resume} brings a task back with the work it had.
 */
public final class TaskPanic {

    /**
     * One task this stopped, or would have stopped.
     *
     * @param name Container name.
     * @param helpers How many helper processes it had.
     * @param surviving Helpers that refused to die, which somebody has to kill by hand.
     */
    public record Stopped(String name, long helpers, List<String> surviving) {

        /**
         * Returns this as plain values, for a caller that has to put it on a wire.
         *
         * @return The task.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("helpers", helpers);
            map.put("surviving", surviving);
            return map;
        }
    }

    /**
     * What a panic did, or would have done.
     *
     * @param tasks Every task that was running, in the order they were stopped.
     * @param surviving Every helper that outlived its stop, across all tasks.
     * @param previewed Whether this only listed what would happen.
     */
    public record Result(List<Stopped> tasks, List<String> surviving, boolean previewed) {

        /** @return How many tasks stopped with nothing left running. */
        public int clean() {
            return (int) tasks.stream().filter(task -> task.surviving().isEmpty()).count();
        }
    }

    private final SokarContext context;

    /**
     * Constructor with the context to act on.
     *
     * @param context Where the runtime and the paths come from.
     */
    public TaskPanic(SokarContext context) {
        this.context = context;
    }

    /**
     * Stops every running task, or reports what that would stop.
     *
     * @param preview Whether to list rather than stop.
     * @return What happened, or would have.
     */
    public Result panic(boolean preview) {

        final List<TaskInventory.Task> running = new TaskInventory(context).tasks().stream()
                .filter(TaskInventory.Task::running)
                .toList();

        if (preview) {
            return new Result(running.stream()
                    .map(task -> new Stopped(task.name(), task.helpers(), List.of())).toList(),
                    List.of(), true);
        }

        final TaskControl control = new TaskControl(context);
        final List<Stopped> stopped = new ArrayList<>();
        final List<String> surviving = new ArrayList<>();

        for (final TaskInventory.Task task : running) {
            // Through the same operation 'task stop' uses, so a panic writes down what a task held
            // that never reached the gate, exactly as a deliberate stop does. A faster path that
            // skipped that would lose the one record of what was lost.
            final TaskControl.Stopped result = control.stop(task.name(), false, false, false);
            stopped.add(new Stopped(task.name(), result.helpers(), result.surviving()));
            surviving.addAll(result.surviving());
        }
        return new Result(List.copyOf(stopped), List.copyOf(surviving), false);
    }
}
