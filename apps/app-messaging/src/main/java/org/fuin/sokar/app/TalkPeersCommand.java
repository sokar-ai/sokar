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

    @Option(names = { "-p", "--project" }, paramLabel = "<name>", required = true,
            description = "Project name, as 'sokar project list' prints it.")
    private String projectName;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    /**
     * Says whether the project's tasks reach each other, which no file lists: they are peers by being tasks of
     * one project, through its conversation, and a project with none is standalone.
     *
     * @param mail The project's mail.
     * @return One line for a person.
     */
    static String tasks(final Mail mail) {
        if (mail.conversations().isEmpty()) {
            return "its tasks do not message each other: the project has no conversation (mail.transports)";
        }
        return "its tasks reach each other by task name, through its "
                + String.join(", ", mail.conversations()) + " conversation";
    }

    /**
     * Returns whom a project's tasks may address by name, besides each other: the file's peers, the people of its
     * conversation, and each person who joined it: mentioned in the room, and in their direct chat only for a message
     * that says {@code "via": "direct"}, an answer to their direct message.
     *
     * @param mail The project's mail.
     * @param members Each person who joined its conversation, to their account.
     * @return The peers, the file's first.
     */
    static java.util.List<Mail.Peer> listed(final Mail mail, final java.util.Map<String, String> members) {
        final java.util.List<Mail.Peer> peers = new java.util.ArrayList<>(mail.peers());
        final Mail.Peer people = mail.people();
        if (people != null) {
            peers.add(people);
        }
        if (!mail.conversations().isEmpty()) {
            final String room = mail.conversations().iterator().next();
            members.forEach((person, account) -> {
                if (peers.stream().noneMatch(peer -> peer.name().equals(person))) {
                    try {
                        peers.add(new Mail.Peer(person, room + ":" + account, Mail.Peer.EXTERNAL));
                    } catch (final org.fuin.sokar.core.project.ProjectException ex) {
                        // A name no peer can carry is reached in the room only.
                    }
                }
            });
        }
        return peers;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final Project project = GateSupport.byName(context, projectName);
        final java.util.List<Mail.Peer> peers = listed(project.mail(), Conversations.members(context, project));
        if (peers.isEmpty()) {
            out.println("no peers named in the project file; " + tasks(project.mail()));
            out.flush();
            return 0;
        }
        final var installed = context.paths().messaging().transportDirectory().byName();
        out.printf("%-16s %-10s %-10s %s%n", "PEER", "TRANSPORT", "TRUST", "REACHED BY");
        for (final Mail.Peer peer : peers) {
            out.printf("%-16s %-10s %-10s %s%n", peer.name(), peer.transport(), peer.trust(),
                    installed.containsKey(peer.transport())
                            ? peer.conversation() ? "the project's own conversation"
                                    : peer.destination().startsWith("@") ? "the room, mentioning " + peer.destination()
                                            + "; with \"via\": \"direct\", their direct chat"
                                    : peer.destination()
                            : "no '" + peer.transport() + "' transport on this machine");
        }
        out.println();
        out.println(tasks(project.mail()));
        out.flush();
        return 0;
    }
}
