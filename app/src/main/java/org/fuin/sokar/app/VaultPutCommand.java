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
            description = "Name to store it under: the provider it is for, as 'sokar providers'"
                    + " lists them.")
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

    /**
     * Reads the credential from a person at a terminal or from standard input, and leaves standard
     * input open.
     * <p>
     * <strong>Never closed.</strong> Closing a reader over standard input closes descriptor 0, and
     * the next file opened takes that number - here the vault itself. The passphrase prompt that
     * follows then switched echo off on a regular file and failed with "Inappropriate ioctl for
     * device", every time the vault was locked.
     *
     * @param name The entry's name, for the prompt.
     * @param tty The console, or {@code null} when there is none.
     * @param in Standard input.
     * @return The value, stripped, or empty when there was none.
     * @throws IOException If standard input cannot be read.
     */
    static String valueFrom(String name, java.io.@org.jspecify.annotations.Nullable Console tty,
            java.io.InputStream in) throws IOException {
        // isTerminal() rather than a null check: since Java 22 a Console is handed out even when
        // standard input is a pipe, and reading a piped secret through readPassword would hang.
        final boolean interactive = tty != null && tty.isTerminal();
        return readValue(interactive,
                () -> tty == null ? new char[0] : tty.readPassword("Value for '%s': ", name),
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    /**
     * Says what is wrong with a name no provider is declared under.
     *
     * @param name The name given.
     * @param providers The providers declared here.
     * @param agents The agents installed here.
     * @return One sentence, without the "sokar: " prefix.
     */
    static String unknownName(String name, java.util.Set<String> providers,
            java.util.Set<String> agents) {
        final String declared = providers.isEmpty() ? "none"
                : String.join(", ", new java.util.TreeSet<>(providers));
        if (agents.contains(name)) {
            // A task running this agent still finds it, as the fallback for vaults written before
            // credentials were keyed by provider - so "nothing will find it" would be untrue.
            return "'" + name + "' is an agent's name, not a provider's. A task running " + name
                    + " still finds this credential, but it belongs under the provider that issued"
                    + " it: store it with 'sokar vault put <provider>' (declared: " + declared
                    + "; 'sokar providers' shows which agent uses which), then 'sokar vault remove "
                    + name + "'";
        }
        return "no provider '" + name + "' is declared (declared: " + declared + "); a task will"
                + " only find this credential if something asks for it by that name";
    }

    /**
     * Says so when nothing will look this credential up, or when its kind cannot be brokered.
     * <p>
     * Checked here as well as at task start, because storing is where the choice is made and a
     * task may not be run for days. A name nobody knows is worth saying whether or not a kind
     * was given: the commonest way to get this wrong is to type the agent's name instead of the
     * provider's.
     *
     * @param err Where to report.
     */
    private void warnIfNothingWillUseIt(PrintWriter err) {
        final var provider = context.providers().get(name);
        if (provider == null) {
            // Not refused: a credential may be stored before its provider is declared, and an
            // operator may have names of their own. Said once, so a typo is findable.
            java.util.Set<String> agents;
            try {
                agents = context.paths().agentDirectory().executables().stream()
                        .map(path -> path.getFileName().toString()
                                .substring(org.fuin.sokar.agent.api.AgentDirectory.PREFIX.length()))
                        .collect(java.util.stream.Collectors.toSet());
            } catch (RuntimeException ex) {
                agents = java.util.Set.of();
            }
            err.println("sokar: " + unknownName(name, context.providers().keySet(), agents));
            err.flush();
            return;
        }
        if (type == null) {
            return;
        }
        final String reason = provider.unbrokerableReason(type);
        if (reason != null) {
            err.println("sokar: '" + name + "' cannot use a '" + type + "' credential"
                    + " through the proxy - " + reason);
            err.flush();
        }
    }

    /**
     * Reads the credential, without putting it on the screen when a person is typing it.
     * <p>
     * <strong>This was a leak, and the product contradicted itself about it.</strong> The vault
     * passphrase has always been read through {@code Console.readPassword}, which does not echo.
     * A credential was read with a plain {@code readLine}, so pasting one into a terminal printed
     * it and left it in the scrollback - which many terminals persist to disk. Advice to "store it
     * at the machine rather than over the wire" was therefore recommending the path that wrote the
     * secret down.
     * <p>
     * The piped form stays exactly as it was. {@code printf %s ... | sokar vault put} is how
     * scripts do this and how the acceptance suite does it, and there is no terminal there to not
     * echo to.
     *
     * @param interactive Whether a person is typing at a terminal.
     * @param typed Reads without echo. Called only when {@code interactive}.
     * @param piped Standard input. Read only when not {@code interactive}.
     * @return The value, stripped, or empty when there was none.
     * @throws IOException If standard input cannot be read.
     */
    static String readValue(boolean interactive, java.util.function.Supplier<char[]> typed,
            BufferedReader piped) throws IOException {
        if (interactive) {
            final char[] secret = typed.get();
            if (secret == null) {
                return "";
            }
            try {
                return new String(secret).strip();
            } finally {
                // The String is what gets stored, so this only shortens one copy's life. Worth
                // doing anyway: it is the copy that exists for no reason after this point.
                java.util.Arrays.fill(secret, '\0');
            }
        }
        final String line = piped.readLine();
        return line == null ? "" : line.strip();
    }

    @Override
    public Integer call() throws IOException {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final String value = valueFrom(name, System.console(), System.in);
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
        warnIfNothingWillUseIt(err);
        final String suspicious = new org.fuin.sokar.vault.VaultEntry(value, type).suspicious();
        if (suspicious != null) {
            err.println("sokar: check what you stored - " + suspicious);
            err.flush();
        }
        out.flush();
        return 0;
    }
}
