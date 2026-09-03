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

    @Option(names = "--seconds", paramLabel = "<n>",
            description = "Stop after this long. Zero means run until killed.")
    private int seconds;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final SigningKey key;
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

    private SigningKey fromVault() {
        final VaultFile vault = new VaultFile(
                new SokarPaths(XdgPaths.current(), Path.of("")).xdg().data().resolve("vault.bin"));
        final String seed = vault.read(passphrase()).get(keyName);
        if (seed == null) {
            throw new VaultException("The vault has no entry named '" + keyName + "'");
        }
        return new SigningKey(java.util.Base64.getDecoder().decode(seed), keyName);
    }

    private char[] passphrase() {
        final var runner = new org.fuin.sokar.core.process.ProcessCommandRunner();
        return new org.fuin.sokar.vault.PassphraseTiers(
                // The keyring first here, unlike 'vault unlock': this runs per task, and the whole
                // point of the cache is that it answers without asking anyone.
                org.fuin.sokar.vault.KernelKeyring.source(VaultUnlockCommand.KEY),
                new org.fuin.sokar.vault.SystemdCredential(runner, systemdCredential),
                new org.fuin.sokar.vault.CommandPassphrase(runner, passphraseCommand),
                new org.fuin.sokar.vault.ConsolePassphrase("Vault passphrase: ")).require();
    }
}
