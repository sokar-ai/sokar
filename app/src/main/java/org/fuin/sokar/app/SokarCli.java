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
                GateCommand.class, SetupCommand.class, DoctorCommand.class })
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
        System.exit(new CommandLine(new SokarCli(), new SokarFactory(SokarContext.real()))
                .execute(args));
    }
}
