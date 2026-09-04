package org.fuin.sokar.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import org.fuin.sokar.vault.VaultException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Stores one credential in the vault.
 * <p>
 * <strong>The value is read from standard input, never from an argument.</strong> A command-line
 * argument is visible in the host's process list to every user on the machine for as long as the
 * command runs, and it lands in the shell history afterwards. Neither is acceptable for the thing
 * this tool exists to protect.
 */
@Command(name = "put",
        mixinStandardHelpOptions = true,
        description = "Stores a credential, read from standard input.")
public class VaultPutCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<name>",
            description = "Name to store it under, normally the agent's name.")
    private String name;

    @picocli.CommandLine.Option(names = "--type", paramLabel = "<kind>",
            description = "Kind of credential, as the agent names it, for example 'oauth'."
                    + " Stored with the entry, so no task has to repeat it.")
    private String type;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final String value;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            final String line = reader.readLine();
            value = line == null ? "" : line.strip();
        }
        if (value.isEmpty()) {
            err.println("sokar: nothing on standard input. Use: echo <secret> | sokar vault put "
                    + name);
            err.flush();
            return 2;
        }

        try {
            context.vault().update(context.requirePassphrase(), entries -> {
                entries.put(name, new org.fuin.sokar.vault.VaultEntry(value, type));
                return entries;
            });
        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }

        // The value is never echoed, not even truncated: a terminal scrollback is a file.
        out.println("stored    " + name + " (" + (type == null ? "kind not stated" : type) + ", "
                + value.length() + " characters)");
        if (new org.fuin.sokar.vault.VaultEntry(value, type).implausiblyShort()) {
            err.println("sokar: that is only " + value.length() + " characters, which is shorter"
                    + " than any real credential - check it is not a placeholder");
            err.flush();
        }
        out.flush();
        return 0;
    }
}
