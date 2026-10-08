package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Brings a task's repository at the gate up to its source now - the checkout it was started in, or its remote - and
 * tells its agent what moved.
 */
@Command(name = "refresh",
        mixinStandardHelpOptions = true,
        description = "Brings a task's repository at the gate up to its source - the checkout it was started in, or its"
                + " remote - and tells its agent what moved; 'git fetch sokar' in the task brings it.")
public class TaskRefreshCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Override
    public java.util.List<String> candidates() {
        return TaskCandidates.all(context);
    }

    @Override
    public String candidateLabel() {
        return "tasks";
    }

    @Parameters(index = "0", arity = "0..1", paramLabel = "TASK",
            description = "Container name, as shown by 'sokar task list'. Default: the task started from the checkout you stand in.")
    private @org.jspecify.annotations.Nullable String container;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final TaskTarget.Found found = TaskTarget.resolve(context, container, java.nio.file.Path.of("."),
                TaskTarget.console(spec.commandLine().getOut()));
        if (found.container() == null) {
            spec.commandLine().getErr().println("sokar: " + found.refusal());
            if ((Object) this instanceof Suggests suggests) {
                Suggests.offer(spec.commandLine().getErr(), suggests);
            }
            spec.commandLine().getErr().flush();
            return found.code();
        }
        container = found.container();
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final TaskRefresh.Result result = new TaskRefresh(context).refresh(container);
        switch (result.outcome()) {
            case MOVED -> {
                result.moved().forEach((branch, commit) -> out.printf("moved     %s -> %s%n", branch,
                        commit.substring(0, Math.min(12, commit.length()))));
                out.println(result.told() ? "told      its agent: 'git fetch sokar' brings it"
                        : "not told  " + (result.detail().isEmpty() ? "it has no mailbox" : result.detail()));
                out.flush();
                return 0;
            }
            case UNCHANGED -> {
                out.println("unchanged the gate holds what its source holds");
                out.flush();
                return 0;
            }
            case NO_SUCH_TASK -> {
                err.println("sokar: " + result.detail());
                Suggests.offer(err, this);
                err.flush();
                return 66;
            }
            default -> {
                err.println("sokar: " + result.detail());
                err.flush();
                return result.outcome() == TaskRefresh.Outcome.NOT_GATED ? 69 : 70;
            }
        }
    }
}
