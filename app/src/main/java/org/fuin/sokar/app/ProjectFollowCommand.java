package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Tells this account to take a project's configuration from a repository.
 * <p>
 * <strong>A person does this and nothing else does.</strong> No commit adds a project and nothing
 * discovers one: a configuration source that can enrol further configuration sources is a source
 * that grows where nobody is looking.
 */
@Command(name = "follow",
        mixinStandardHelpOptions = true,
        description = "Takes a project's configuration from its repository, from now on.")
public class ProjectFollowCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<name>", description = "The project's name.")
    private String name;

    @Parameters(index = "1", paramLabel = "<url>",
            description = "Where its repository is. Fetched with this account's own credentials.")
    private String url;

    @picocli.CommandLine.Option(names = "--accept-rewrite",
            description = "Takes a signed commit that is not a descendant of the one in force."
                    + " Say this only when you know why the history moved.")
    private boolean acceptRewrite;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final FollowedProjects projects = new FollowedProjects(context.paths().followed());
        try {
            projects.follow(name, url);
        } catch (final IllegalArgumentException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
        if (acceptRewrite) {
            // Forgetting what is in force is the whole of accepting: the next reconcile then has
            // nothing to descend from and applies what it verifies.
            final FollowedProjects.Followed known = projects.find(name);
            projects.write(new FollowedProjects.Followed(known.name(), known.url(), "",
                    known.at(), known.outcome(), known.detail()));
        }
        // Once, now, rather than at the next tick: somebody who typed this wants to know whether it
        // works, and a refusal an hour later is a refusal nobody connects to what they did.
        final Reconcile.Result result = new Reconcile(context).run(projects.find(name));
        projects.write(Reconcile.after(projects.find(name), result));

        out.println("following  " + name + "  " + url);
        switch (result.outcome()) {
            case APPLIED, UNCHANGED -> out.println("applied    " + result.commit());
            case REWRITTEN -> {
                err.println("refused    " + result.detail());
                err.flush();
                out.flush();
                return 70;
            }
            case REFUSED -> {
                err.println("refused    " + result.detail());
                err.println("           the project is followed; nothing of it is in force");
                err.flush();
                out.flush();
                return 70;
            }
            default -> {
                err.println(result.outcome().name().toLowerCase(java.util.Locale.ROOT)
                        + "    " + result.detail());
                err.flush();
                out.flush();
                return 70;
            }
        }
        out.flush();
        return 0;
    }
}
