package org.fuin.sokar.app;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists the projects this machine has run tasks for.
 * <p>
 * The same rendering {@code sokar project} does with no verb. It exists so the verb can be written
 * where a reader expects one, beside {@code delete}.
 */
@Command(name = "list",
        mixinStandardHelpOptions = true,
        description = "Lists the projects this machine has run tasks for.")
public class ProjectListCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final ProjectsCommand listing = new ProjectsCommand();
        listing.setContext(context);
        return listing.render(spec.commandLine().getOut());
    }
}
