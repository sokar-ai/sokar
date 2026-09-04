package org.fuin.sokar.app;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Entry point of the {@code sokar} binary.
 * <p>
 * Picocli instantiates the command object, so this is a normal class rather than a utility class.
 */
@Command(name = "sokar",
        mixinStandardHelpOptions = true,
        versionProvider = SokarVersion.class,
        description = "Runs AI agent tasks inside hardened, rootless containers.",
        subcommands = { TaskCommand.class, ShieldCommand.class, VaultCommand.class,
                GateCommand.class, AgentsCommand.class, SetupCommand.class, DoctorCommand.class })
public class SokarCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        // Invoked without a subcommand. Usage on stderr and a non-zero exit, so that a script
        // that forgets the subcommand fails instead of looking successful.
        spec.commandLine().usage(spec.commandLine().getErr());
        return 2;
    }

    /**
     * Runs the command line.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        try {
            System.exit(new CommandLine(new SokarCli(), new SokarFactory(SokarContext.real()))
                    .execute(args));
        } catch (final Error ex) {
            if (!missingWorkingDirectory(ex)) {
                throw ex;
            }
            System.err.println("sokar: the current directory no longer exists - cd to one that "
                    + "does. Nothing was read or written.");
            System.exit(2);
        }
    }

    /**
     * Says whether a failure is the shell sitting in a deleted directory.
     * <p>
     * It happens after a {@code clean} build removes a directory a shell is parked in, and it
     * defeats every command, because the first file operation initialises {@code user.dir} and
     * that is what fails. It cannot be checked for in advance: the check would be a file
     * operation and would trigger it. So it is caught, identified by the message the JDK raises
     * it with, and anything else is rethrown untouched.
     *
     * @param error The error that escaped.
     * @return {@code true} when the working directory is gone.
     */
    static boolean missingWorkingDirectory(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            final String message = cause.getMessage();
            if (message != null && message.contains("current working directory")) {
                return true;
            }
        }
        return false;
    }
}
