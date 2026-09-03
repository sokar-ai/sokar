package org.fuin.sokar.app;

import picocli.CommandLine.Command;

/**
 * The {@code sokar task} command group.
 */
@Command(name = "task",
        mixinStandardHelpOptions = true,
        description = "Runs and inspects agent tasks.",
        subcommands = { TaskRunCommand.class })
public class TaskCommand {

}
