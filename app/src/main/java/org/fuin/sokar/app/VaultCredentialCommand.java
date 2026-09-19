package org.fuin.sokar.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Answers git when it asks for the password of an https repository.
 * <p>
 * <strong>git's own protocol, not an invention.</strong> git runs a credential helper with
 * {@code get}, writes {@code protocol=}, {@code host=} and a blank line to its standard input, and
 * reads {@code username=} and {@code password=} back. Sokar wires it up per command
 * ({@code git -c credential.https://host.helper=...}), so nothing is written into any git config
 * and the helper is offered to that host alone.
 * <p>
 * <strong>Why the token is not an argument.</strong> {@code /proc/<pid>/cmdline} is world readable
 * on both supported distributions, so a token on a command line is readable by every account on
 * the machine. What travels as an argument here is the <em>name of the vault entry</em>; the value
 * is read from the vault by this process and written to one pipe.
 */
@Command(name = "credential",
        mixinStandardHelpOptions = true,
        description = "Answers git's credential protocol from the vault. Run by git, not by hand.")
public class VaultCredentialCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<operation>",
            description = "What git asks: get, store or erase. Only 'get' answers.")
    private String operation = "get";

    @Option(names = "--entry", paramLabel = "<name>",
            description = "Vault entry holding the token, as 'sokar vault list' shows it.")
    private String entry;

    @Option(names = "--env", paramLabel = "<variable>",
            description = "Environment variable holding the token, for a value this machine did"
                    + " not store.")
    private String variable;

    @Option(names = "--file", paramLabel = "<path>",
            description = "File holding the token, for a value this machine did not store.")
    private String file;

    @Option(names = "--username", paramLabel = "<name>",
            description = "What to answer as the user. Default: ${DEFAULT-VALUE}")
    private String username = "x-access-token";

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // 'store' and 'erase' are git offering to remember or forget. The vault is not git's to
        // write, so both are accepted and do nothing - which is what git expects of a read-only
        // helper, and is quieter than failing on every successful fetch.
        if (!"get".equals(operation)) {
            return 0;
        }
        // Read git's request even though every field is already known: git waits for the helper
        // to consume it, and a helper that answers without reading can deadlock on a full pipe.
        try (BufferedReader asked = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = asked.readLine();
            while (line != null && !line.isBlank()) {
                line = asked.readLine();
            }
        }

        final String secret = read(err);
        if (secret == null) {
            // Answering nothing makes git ask its next helper or fail with its own message, which
            // is better than this inventing one - but the reason goes to standard error, where
            // git shows it.
            return 0;
        }
        out.println("username=" + username);
        out.println("password=" + secret);
        out.flush();
        return 0;
    }

    /**
     * Returns the secret from wherever this was told it lives.
     * <p>
     * Three sources because a person working on their own machine may already have the value -
     * exported, or in a file - and should not have to keep a second copy of it in a vault. What
     * the vault adds is being encrypted and shuttable; the other two are as protected as whatever
     * holds them, which is the person's choice to make and the machine's job to say.
     *
     * @param err Where to explain a miss.
     * @return The value, or {@code null} when there is none.
     */
    private @org.jspecify.annotations.Nullable String read(final PrintWriter err) {
        if (variable != null) {
            final String value = System.getenv(variable);
            if (value == null || value.isBlank()) {
                err.println("sokar: $" + variable + " is not set in this environment");
                err.flush();
                return null;
            }
            return value.strip();
        }
        if (file != null) {
            try {
                final String value = java.nio.file.Files.readString(
                        java.nio.file.Path.of(file)).strip();
                if (value.isEmpty()) {
                    err.println("sokar: " + file + " is empty");
                    err.flush();
                    return null;
                }
                return value;
            } catch (final IOException ex) {
                err.println("sokar: cannot read " + file + ": " + ex.getMessage());
                err.flush();
                return null;
            }
        }
        if (entry == null) {
            err.println("sokar: say where the value is: --entry, --env or --file");
            err.flush();
            return null;
        }
        final var readable = context.readableCredentials();
        if (readable.isEmpty()) {
            err.println("sokar: the vault is not open, so '" + entry + "' cannot be read");
            err.flush();
            return null;
        }
        final var held = readable.get().get(entry);
        if (held == null) {
            err.println("sokar: the vault has no entry named '" + entry + "'");
            err.flush();
            return null;
        }
        return held.value();
    }
}
