package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Lists who this project's tasks may address, and how a message would get there.
 * <p>
 * The addresses are printed here and never inside a container: a task addresses a name, and what
 * that name resolves to is the host's business. Whether the transport carrying it is installed is
 * printed beside it, because a peer configured against a transport this machine does not have looks
 * configured and is not.
 */
@Command(name = "peers",
        mixinStandardHelpOptions = true,
        description = "Lists the peers this project's tasks may address.")
public class TalkPeersCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: project.yml")
    private Path projectFile = Path.of("project.yml");

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
        final Project project = ProjectReader.read(projectFile);
        if (project.mail().peers().isEmpty()) {
            out.println("no peers - this project's tasks address nobody");
            out.flush();
            return 0;
        }
        final var installed = context.paths().transportDirectory().byName();
        out.printf("%-16s %-10s %-10s %s%n", "PEER", "TRANSPORT", "TRUST", "REACHED BY");
        for (final Mail.Peer peer : project.mail().peers()) {
            out.printf("%-16s %-10s %-10s %s%n", peer.name(), peer.transport(), peer.trust(),
                    installed.containsKey(peer.transport()) ? peer.destination()
                            : "no '" + peer.transport() + "' transport on this machine");
        }
        out.flush();
        return 0;
    }
}
