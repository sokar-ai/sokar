package org.fuin.sokar.app;

import picocli.CommandLine.Command;

/**
 * The {@code sokar shield} command group.
 */
@Command(name = "shield",
        mixinStandardHelpOptions = true,
        description = "Inspects the egress firewall.",
        subcommands = { ShieldReadCommand.class, ShieldWatchCommand.class,
                ShieldDnsCommand.class, ShieldSubscribeCommand.class,
                ShieldSetsCommand.class })
public class ShieldCommand {

}
