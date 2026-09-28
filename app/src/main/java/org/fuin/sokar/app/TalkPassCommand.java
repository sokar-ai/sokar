package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.ProjectReader;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Moves a task's messages along once: out through the filter and a transport, in through the
 * signature check, and the filter's answers into the agent's inbox.
 * <p>
 * By hand, for now. Nothing runs this on a schedule yet, which is worth knowing rather than
 * discovering: a message sits in the outbox until somebody - or later the daemon - moves it.
 */
@Command(name = "pass",
        mixinStandardHelpOptions = true,
        description = "Moves this task's messages along once.")
public class TalkPassCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Option(names = { "-p", "--project" }, paramLabel = "<name>",
            description = "The task's project, for its peers, as 'sokar project list' prints it.")
    private @Nullable String projectName;

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
        final Mailbox mailbox = new Mailbox(context.paths().mailbox(container));
        if (!mailbox.exists()) {
            err.println("sokar: " + container + " has no mailbox");
            err.flush();
            return 1;
        }
        if (projectName == null) {
            err.println("sokar: say which project the task belongs to, with --project");
            err.flush();
            return 2;
        }
        final var pass = new MessagePass(context.runner(),
                HostKey.loadOrCreate(context.paths().messageKey(), "sokar@" + hostName()),
                context.paths().messageFilter(), context.paths().transportDirectory());
        final MessagePass.Report report = pass.run(mailbox,
                GateSupport.byName(context, projectName).mail(),
                KnownPeers.of(context));

        report.polled().failures().forEach((transport, why) ->
                err.println("transport " + transport + " - " + why));
        if (!report.polled().arrivals().isEmpty()) {
            out.println("fetched   " + report.polled().arrivals().size() + " by "
                    + String.join(", ", new java.util.TreeSet<>(
                            report.polled().arrivals().values())));
        }
        out.println("taken     " + report.taken().size());
        if (!report.filtered().ran()) {
            // Not a warning among others: this is the reason nothing left, and it is the state an
            // operator has to act on.
            err.println("sokar: " + report.filtered().detail());
            err.flush();
        } else if (!report.filtered().detail().isEmpty()) {
            err.println("sokar: " + report.filtered().detail());
            err.flush();
        }
        out.println("queued    " + report.dispatched().queued().size()
                + (report.dispatched().held().isEmpty() ? ""
                        : ", held " + report.dispatched().held().size()));
        report.sent().forEach((transport, result) -> out.println("sent      " + result.sent().size()
                + " by " + transport
                + (result.deferred().isEmpty() ? "" : ", " + result.deferred().size() + " waiting")
                + (result.refused().isEmpty() ? "" : ", " + result.refused().size() + " refused")));
        out.println("delivered " + report.delivered().delivered().size()
                + (report.delivered().held().isEmpty() ? ""
                        : ", held " + report.delivered().held().size()));
        report.delivered().held().forEach(held ->
                err.println("held      " + held.message() + " - " + held.reason()));
        report.delivered().duplicates().forEach(repeat -> out.println(
                "dropped   " + repeat.message() + " - " + repeat.id() + " was delivered before"));
        out.println("answers   " + report.bounced().size());
        out.flush();
        err.flush();
        return 0;
    }

    private String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (final java.net.UnknownHostException e) {
            // The name is a label in an allowed_signers line, not an address. A machine that cannot
            // say its own name still signs; it just says less about itself.
            return "localhost";
        }
    }
}
