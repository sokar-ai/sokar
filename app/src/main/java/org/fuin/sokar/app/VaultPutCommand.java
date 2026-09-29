package org.fuin.sokar.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import org.fuin.sokar.vault.VaultException;
import org.jspecify.annotations.Nullable;
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
    private @Nullable String type;

    @picocli.CommandLine.Option(names = "--from-file", paramLabel = "<path>",
            description = "Reads the value from this file ON THIS MACHINE, instead of from"
                    + " standard input. For a key that is already here: nothing has to be sent,"
                    + " and an interface can ask for this without ever holding the value.")
    private @Nullable String fromFile;

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
     * Returns the key in a form this machine reads, converting it if it has to.
     * <p>
     * <strong>The machine does this, not the person.</strong> {@code ssh-keygen} writes RSA keys
     * in its own container by default, which is not a format read here - and the first answer was
     * a message telling somebody to run {@code ssh-keygen -p -m PEM} themselves. That is our work
     * to do: the tool is on this machine, the conversion is one command, and what should be easy
     * is using your own key rather than knowing which of its formats we happened to implement.
     * <p>
     * <strong>The original is never touched.</strong> {@code ssh-keygen -p} rewrites a key file
     * in place, so the conversion runs on a copy in a directory only this account can read, and
     * the copy is overwritten and removed afterwards. Somebody's daily key is not ours to rewrite.
     *
     * @param text The key as it arrived.
     * @param out Where to report what was done.
     * @param err Where to report what could not be.
     * @return The key to store, or {@code null} when it could not be converted.
     */
    private @org.jspecify.annotations.Nullable String converted(final String text,
            final PrintWriter out, final PrintWriter err) {

        if (!org.fuin.sokar.vault.OpenSshPrivateKey.looksLikeOne(text)) {
            return text;
        }
        final org.fuin.sokar.vault.OpenSshPrivateKey.Described described =
                org.fuin.sokar.vault.OpenSshPrivateKey.describe(text);
        if (described == null || described.usable() || described.encrypted()) {
            // Ed25519 is read as it is, and an encrypted key is refused where that is explained.
            return text;
        }
        java.nio.file.Path directory = null;
        try {
            directory = java.nio.file.Files.createTempDirectory("sokar-key-");
            java.nio.file.Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            final java.nio.file.Path copy = directory.resolve("key");
            java.nio.file.Files.writeString(copy, text);
            java.nio.file.Files.setPosixFilePermissions(copy,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            final var result = context.runner().run(org.fuin.sokar.core.process.Command.of(
                    "ssh-keygen", "-p", "-m", "PEM", "-N", "", "-P", "", "-f", copy.toString()));
            if (!result.successful()) {
                err.println("sokar: this key is in OpenSSH's own format and converting it here"
                        + " failed: " + result.standardError().strip());
                err.flush();
                return null;
            }
            out.println("read      an OpenSSH-wrapped key; converted a copy to PEM to store it"
                    + " (your file is untouched)");
            return java.nio.file.Files.readString(copy);
        } catch (final IOException ex) {
            err.println("sokar: cannot convert this key here: " + ex.getMessage());
            err.flush();
            return null;
        } finally {
            if (directory != null) {
                shred(directory);
            }
        }
    }

    /**
     * Removes a directory that held a key, overwriting what was in it first.
     * <p>
     * Not a guarantee on a copy-on-write filesystem, and worth doing anyway: it is the difference
     * between a key that is gone and a key that is merely unlinked.
     *
     * @param directory What to remove.
     */
    private static void shred(final java.nio.file.Path directory) {
        try (var entries = java.nio.file.Files.list(directory)) {
            for (final java.nio.file.Path file : entries.toList()) {
                try {
                    final long size = java.nio.file.Files.size(file);
                    java.nio.file.Files.write(file, new byte[(int) Math.min(size, 1 << 20)]);
                } catch (final IOException ex) {
                    // Overwriting is the part that can fail harmlessly; removal below is what
                    // matters and is attempted either way.
                    continue;
                }
                java.nio.file.Files.deleteIfExists(file);
            }
            java.nio.file.Files.deleteIfExists(directory);
        } catch (final IOException ex) {
            return;
        }
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
        if (GitCredentialNames.isOne(name)) {
            // Not a provider's name and not meant to be: these are read by the git commands this
            // machine runs. Warning that "no provider is declared" for one sent people looking
            // for a mistake they had not made.
            return;
        }
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
        if (line == null) {
            return "";
        }
        if (line.strip().startsWith("-----BEGIN")) {
            // A key file is many lines, and this read one. '< ~/.ssh/id_ed25519' therefore stored
            // the armour line alone, warned that it "contains spaces", and failed days later when
            // something tried to sign with it. The whole file is read when the first line says
            // that is what this is.
            final StringBuilder whole = new StringBuilder(line).append('\n');
            String next = piped.readLine();
            while (next != null) {
                whole.append(next).append('\n');
                next = piped.readLine();
            }
            return whole.toString();
        }
        return line.strip();
    }

    @Override
    public Integer call() throws IOException {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        if (TaskSecrets.reserved(name)) {
            // Refused before anything is asked for: a name here would look like a task's own token.
            err.println("sokar: names starting with '" + TaskSecrets.PREFIX + "' belong to tasks; choose another");
            err.flush();
            return 64;
        }

        String value;
        if (fromFile == null) {
            value = valueFrom(name, System.console(), System.in);
        } else {
            // The machine reads its own disk. This is what lets an interface offer "use the key
            // that is already here" without the value crossing a socket or a person retyping it.
            try {
                value = java.nio.file.Files.readString(java.nio.file.Path.of(fromFile));
            } catch (final IOException ex) {
                err.println("sokar: cannot read " + fromFile + ": " + ex.getMessage());
                err.flush();
                return 70;
            }
            if (fromFile.endsWith(".pub")) {
                // The public half is not a credential. It is the commonest mistake there is here,
                // and it fails silently later rather than now.
                err.println("sokar: " + fromFile + " is a public key. A machine signs with the"
                        + " private half - the same path without '.pub'.");
                err.flush();
                return 70;
            }
        }
        if (org.fuin.sokar.vault.OpenSshPrivateKey.looksLikeAnyPrivateKey(value)) {
            // What a person has is the file they use every day. An Ed25519 key is kept as the
            // seed inside it, which is what every entry written until now holds; anything else is
            // kept as the file, because there is nothing smaller that is still the key.
            //
            // It is READ BACK before it is stored. A value that goes in and cannot come out is
            // what happened to an RSA key this morning: stored whole, called present and ready by
            // every check, and undecodable at the first fetch - where the machine blamed the
            // vault for being empty.
            try {
                value = converted(value, out, err);
                if (value == null) {
                    return 70;
                }
                value = org.fuin.sokar.vault.StoredKey.toStore(value);
                final org.fuin.sokar.vault.AgentKey read =
                        org.fuin.sokar.vault.StoredKey.of(value, name);
                if (type == null) {
                    type = "ssh-key";
                }
                out.println("read      " + read.authorizedKeysLine().split("\\s+")[0]
                        + "; this machine can sign with it");
            } catch (final VaultException ex) {
                err.println("sokar: " + ex.getMessage());
                err.flush();
                return 70;
            }
        }
        if (value.isEmpty()) {
            err.println("sokar: nothing on standard input. Use: echo <secret> | sokar vault put "
                    + name);
            err.flush();
            return 2;
        }
        final String stored = value;

        try {
            context.vault().update(context.requirePassphrase(), entries -> {
                entries.put(name, new org.fuin.sokar.vault.VaultEntry(stored, type));
                return entries;
            });
        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }

        // The value is never echoed, not even truncated: a terminal scrollback is a file.
        out.println("stored    " + name + " (" + (type == null ? "kind not stated" : type) + ", "
                + stored.length() + " characters)");
        warnIfNothingWillUseIt(err);
        final String suspicious = new org.fuin.sokar.vault.VaultEntry(stored, type).suspicious();
        if (suspicious != null) {
            err.println("sokar: check what you stored - " + suspicious);
            err.flush();
        }
        out.flush();
        return 0;
    }
}
