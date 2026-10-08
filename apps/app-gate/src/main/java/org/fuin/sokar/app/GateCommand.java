package org.fuin.sokar.app;

import picocli.CommandLine.Command;

/**
 * The {@code sokar gate} command group.
 */
@Command(name = "gate",
        mixinStandardHelpOptions = true,
        description = "Reviews and forwards what an agent pushed.",
        subcommands = { GateServeCommand.class, GatePendingCommand.class,
                GateReviewCommand.class, GateApproveCommand.class, GateRejectCommand.class,
                GateBackupCommand.class, GateRestoreCommand.class,
                GateCheckoutCommand.class, GateProtectCommand.class,
                GateCheckCommand.class })
public class GateCommand {

}
