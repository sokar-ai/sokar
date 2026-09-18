package org.fuin.sokar.app;

import picocli.CommandLine.Command;

/**
 * The {@code sokar vault} command group.
 */
@Command(name = "vault",
        mixinStandardHelpOptions = true,
        description = "Manages stored credentials.",
        subcommands = { VaultInitCommand.class, VaultPutCommand.class, VaultImportCommand.class, VaultListCommand.class,
                VaultRemoveCommand.class,
                VaultServeCommand.class,
                VaultRelayCommand.class,
                VaultAgentCommand.class, VaultUnlockCommand.class,
                VaultLockCommand.class, VaultPassphraseCommand.class,
                VaultLoginCommand.class,
                VaultDevicesCommand.class, VaultRevokeCommand.class })
public class VaultCommand {

}
