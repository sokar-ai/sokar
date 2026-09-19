package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.SshAgentServer;
import org.fuin.sokar.vault.VaultException;
import org.fuin.sokar.vault.VaultFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Runs the ssh-agent that signs for a task container.
 * <p>
 * The socket it creates is what gets bind-mounted into the container as {@code SSH_AUTH_SOCK}. The
 * key itself stays in this process: a container can ask for a signature and cannot obtain the key
 * that produces it.
 */
@Command(name = "agent",
        mixinStandardHelpOptions = true,
        description = "Runs an ssh-agent that signs with a key from the vault.")
public class VaultAgentCommand implements Callable<Integer> {

    @Option(names = "--socket", paramLabel = "<path>", required = true,
            description = "Where to create the agent socket.")
    private Path socket;

    @Option(names = "--key", paramLabel = "<name>",
            description = "Vault entry holding the key seed. Default: ${DEFAULT-VALUE}")
    private String keyName = "ssh.default";

    @Option(names = "--passphrase-command", paramLabel = "<command>",
            description = "Command whose first output line is the vault passphrase.")
    private String passphraseCommand;

    @Option(names = "--systemd-credential", paramLabel = "<file>",
            description = "systemd-creds encrypted file holding the vault passphrase.")
    private String systemdCredential;

    @Option(names = "--ephemeral",
            description = "Generates a throwaway key instead of reading the vault. For testing.")
    private boolean ephemeral;

    @Option(names = "--print-public-key",
            description = "Prints the public key in authorized_keys form and keeps running.")
    private boolean printPublicKey;

    @Option(names = "--pid-file", paramLabel = "<file>",
            description = "Writes this process's id here, so the poststop hook can reap it.")
    private Path pidFile;

    @Option(names = "--seconds", paramLabel = "<n>",
            description = "Stop after this long. Zero means run until killed.")
    private int seconds;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final org.fuin.sokar.vault.AgentKey key;
        try {
            key = ephemeral ? SigningKey.generate("sokar-ephemeral") : fromVault();
        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }

        try (SshAgentServer agent = new SshAgentServer(socket, List.of(key))) {

            if (printPublicKey) {
                out.println(key.authorizedKeysLine());
            }
            if (pidFile != null) {
                try {
                    org.fuin.sokar.wire.HelperPid.record(pidFile);
                } catch (java.io.IOException ex) {
                    err.println("sokar: cannot write " + pidFile + ": " + ex.getMessage());
                    err.flush();
                }
            }
            out.println("agent     " + agent.socketPath());
            out.flush();

            final Thread serving = Thread.ofVirtual().start(agent);
            if (seconds > 0) {
                serving.join(java.time.Duration.ofSeconds(seconds));
            } else {
                serving.join();
            }
            return 0;
        }
    }

    private org.fuin.sokar.vault.AgentKey fromVault() {
        final SokarPaths paths = new SokarPaths(XdgPaths.current(), Path.of(""));
        final VaultFile vault = new VaultFile(paths.vaultFile());
        final var entry = vault.read(opener(paths)).get(keyName);
        if (entry == null) {
            throw new VaultException("The vault has no entry named '" + keyName + "'");
        }
        // Any shape the vault holds: a seed from before, or a key file of whatever kind.
        return org.fuin.sokar.vault.StoredKey.of(entry.value(), keyName);
    }

    private VaultFile.Opener opener(SokarPaths paths) {
        // A device's share counts here too: this runs per task, and a machine a device unlocked
        // must serve a task exactly as one a person unlocked does.
        final java.util.Optional<byte[]> share = VaultShare.held(paths);
        if (share.isPresent()) {
            return VaultFile.Opener.share(share.get());
        }
        final var runner = new org.fuin.sokar.core.process.ProcessCommandRunner();
        return VaultFile.Opener.passphrase(new org.fuin.sokar.vault.PassphraseTiers(
                // The keyring first here, unlike 'vault unlock': this runs per task, and the whole
                // point of the cache is that it answers without asking anyone.
                org.fuin.sokar.vault.KernelKeyring.source(paths.vaultKeyringKey()),
                new org.fuin.sokar.vault.SystemdCredential(runner, systemdCredential),
                new org.fuin.sokar.vault.CommandPassphrase(runner, passphraseCommand),
                new org.fuin.sokar.vault.ConsolePassphrase("Vault passphrase: ")).require());
    }
}
