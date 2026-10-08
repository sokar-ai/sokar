package org.fuin.sokar.app;

import picocli.CommandLine.Command;

/**
 * The {@code sokar shield} command group.
 */
@Command(name = "shield",
        mixinStandardHelpOptions = true,
        description = "Inspects and changes what a task may reach.",
        subcommands = { ShieldReadCommand.class, ShieldWatchCommand.class,
                ShieldDnsCommand.class, ShieldSubscribeCommand.class,
                ShieldSetsCommand.class, ShieldEgressCommand.class })
public class ShieldCommand {

}
