package org.fuin.sokar.acceptance;

import java.io.IOException;
import java.util.Optional;
import org.fuin.sokar.machines.Ssh;

/**
 * Watches the sessions of every task the scenario's account runs while a terminal waits, for an agent that repeats
 * a failure.
 * <p>
 * Every task of the account, not only the ones a step started: a scenario that starts its agent by typing
 * {@code sokar task start} at the terminal is the one that waits longest on a model.
 */
final class TaskHistories implements Terminal.Loops {

    private final World world;

    private LoopWatch watch;

    /**
     * Creates one for a scenario.
     *
     * @param world The scenario.
     */
    TaskHistories(World world) {
        this.world = world;
        this.watch = new LoopWatch(world.toolFailure(), LoopWatch.repeats());
    }

    @Override
    public void reset() {
        // A marker declared after the terminal was opened counts from the next wait.
        watch = new LoopWatch(world.toolFailure(), LoopWatch.repeats());
    }

    @Override
    public Optional<String> check() throws IOException {
        final Ssh.Output printed = world.run(LoopWatch.COMMAND);
        if (printed.status() != 0) {
            // No podman, or no task yet: nothing that could loop.
            return Optional.empty();
        }
        return watch.check(LoopWatch.histories(printed.out()));
    }
}
