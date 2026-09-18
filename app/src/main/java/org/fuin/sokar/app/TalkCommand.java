package org.fuin.sokar.app;

import picocli.CommandLine.Command;

/**
 * What a task says to another, and what it was told.
 * <p>
 * Messages travel through a task's mailbox: the agent writes into its outbox and reads its inbox,
 * and everything between - the filter, the signature, the transport - happens on the host. These
 * verbs are how a person sees that and moves it along by hand.
 */
@Command(name = "talk",
        mixinStandardHelpOptions = true,
        description = "Shows and moves the messages a task exchanges.",
        subcommands = { TalkPeersCommand.class, TalkPassCommand.class, TalkHeldCommand.class,
                TalkReleaseCommand.class, TalkHoldCommand.class,
                TalkVerifyCommand.class, TalkSayCommand.class,
                TalkKeyCommand.class })
public class TalkCommand {
}
