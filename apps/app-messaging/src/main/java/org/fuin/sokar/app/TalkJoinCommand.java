package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Lets a person into a project's conversation - its room - with an account made for them, and shows the login
 * once.
 * <p>
 * Being in the conversation lets them read and write there. Their messages reach a task only once their key
 * is listed for a peer, as anyone's.
 */
@Command(name = "join",
        mixinStandardHelpOptions = true,
        description = "Lets a person into a project's conversation, and shows their login once.")
public class TalkJoinCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project name.")
    private String projectName;

    @Parameters(index = "1", paramLabel = "<person>", description = "Whom the account is for.")
    private String person;

    @Option(names = "--reset", description = "Give the person's existing account a new password.")
    private boolean reset;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Project project = GateSupport.byName(context, projectName);
        if (project.mail().conversations().isEmpty()) {
            err.println("sokar: '" + projectName + "' has no conversation: no peer is reached through a transport's"
                    + " '<scheme>:' address");
            err.flush();
            return 64;
        }
        final Map<?, ?> said;
        try {
            said = new TaskConversations(context).join(context, project, person, reset);
        } catch (TransportLifecycle.Refused ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return ex.code() == 17 ? 1 : 69;
        }
        // Shown once, never kept: the transport's own words, which carry the password.
        out.println(said.get("shown") instanceof String shown && !shown.isBlank() ? shown.strip()
                : "joined    " + person);
        out.flush();
        return 0;
    }
}
