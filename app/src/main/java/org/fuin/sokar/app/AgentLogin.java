package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.agent.api.Credential;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.vault.VaultEntry;
import org.jspecify.annotations.Nullable;

/**
 * Runs an agent's own login, in a throwaway container, and puts what it produces in the vault.
 * <p>
 * <strong>Why this has to exist.</strong> An agent's tooling is installed into the task image, not
 * onto the node - {@code sokar-agent-claude} puts an agent definition in {@code /usr/libexec} and
 * nothing else. So on a machine that has never had that agent installed by hand there is no binary
 * to log in with, and {@code sokar vault put <provider> --type oauth} asks for a value with no
 * source. That was reported by somebody following the getting-started guide on a clean machine.
 * <p>
 * <strong>Why a container rather than the node.</strong> The image already contains the agent,
 * because Sokar built it. Installing the agent on the node as well would mean two copies that can
 * differ, and the one somebody logs in with would not be the one that runs.
 * <p>
 * <strong>This is not a task container, and the difference matters.</strong> It carries no Sokar
 * annotation, so none of the hooks fire: no egress ruleset, no resolver, no clearance watcher, no
 * broker socket and no vault. It has ordinary network access for the seconds a login takes, and is
 * removed afterwards. A task container is the opposite in every one of those respects - the
 * provider's own host is denied there precisely so an agent must go through the broker.
 * <p>
 * <strong>Sokar does not know how any agent logs in.</strong> The verb comes from the agent's own
 * manifest, and an agent that declares none is answered as unsupported rather than guessed at.
 * Hardcoding one agent's verb would be wrong for every other, which is the same mistake as
 * hardcoding the name of an instructions file.
 */
public final class AgentLogin {

    /** Base image for the login container when nothing else says otherwise. */
    static final String LOGIN_BASE_IMAGE = "ubuntu:24.04";

    /**
     * Home of the user an agent runs as inside the image.
     * <p>
     * The Containerfile creates {@code agent} with a home, and every agent's tooling installs
     * under it. A path a manifest writes with {@code ~} means this one, not the node's.
     */
    static final String AGENT_HOME = "/home/agent";

    /** What happened. */
    public enum Outcome {

        /** Logged in, and the credential is in the vault. */
        STORED,

        /** What would be run, having run nothing. */
        PREVIEWED,

        /** No agent of that name, or none given and more than one installed. */
        NO_SUCH_AGENT,

        /** The agent does not say how to log in. */
        UNSUPPORTED,

        /** The agent does not say where it keeps its credentials, so nothing can be collected. */
        NO_CONFIG_DIRECTORY,

        /**
         * The agent is already signed in on this machine, so logging in again would be a second
         * authorization rather than a first.
         */
        ALREADY_SIGNED_IN,

        /** The login ran and left no credential behind - cancelled, or it failed. */
        NOTHING_TO_COLLECT,

        /** The vault cannot be opened without a passphrase nobody can be asked for here. */
        VAULT_LOCKED,

        /** The image could not be built, or the container could not be run. */
        FAILED
    }

    /**
     * What a login did.
     *
     * @param outcome What happened.
     * @param name The vault key it was stored under, or "".
     * @param type The kind of credential, or "".
     * @param length How many characters arrived, so a caller can show that it worked without
     *        showing what worked. Zero when nothing was stored.
     * @param detail Why it failed, or "".
     */
    public record Result(Outcome outcome, String name, String type, int length, String detail) { }

    private AgentLogin() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static Result failed(Outcome outcome, String detail) {
        return new Result(outcome, "", "", 0, detail);
    }

    /**
     * The project a login image is built from.
     * <p>
     * Not a real project and never registered as one: it exists so the image builder has something
     * to build from. Offline, because nothing about this image is a task and no egress set applies
     * to it - the container it produces is run without any of Sokar's networking.
     *
     * @return A project describing the login image.
     */
    static Project loginProject() {
        return new Project("sokar-login", "Throwaway image for an agent login",
                SecurityClass.OFFLINE, LOGIN_BASE_IMAGE, null);
    }

    /**
     * Logs in with an agent and stores what it produced.
     *
     * @param context The machine.
     * @param agentName Which agent, or {@code null} for the only one installed.
     * @param dryRun Says what it would run and runs nothing.
     * @param out Where progress goes.
     * @return What happened.
     */
    public static Result login(SokarContext context, @Nullable String agentName, boolean dryRun,
            boolean force, PrintWriter out) {

        try (var agents = context.agents()) {

            final java.util.Optional<InstalledAgent> found = agentName != null
                    ? agents.find(agentName)
                    : agents.names().size() == 1 ? agents.find(agents.names().getFirst())
                            : java.util.Optional.empty();
            if (found.isEmpty()) {
                return failed(Outcome.NO_SUCH_AGENT,
                        "no such agent; installed: " + String.join(", ", agents.names()));
            }
            final InstalledAgent agent = found.get();

            final List<String> loginArguments = agent.definition().loginArguments();
            // Null, not empty: an agent whose login is "just run me" declares the section with no
            // arguments, and treating that as "cannot log in" would refuse the commonest shape.
            if (loginArguments == null) {
                return failed(Outcome.UNSUPPORTED, "'" + agent.name() + "' does not say how to log"
                        + " in, so there is nothing to run. Store its credential with"
                        + " 'sokar vault put', or import one it already holds");
            }
            final String configDirectory = agent.definition().configDirectory();
            if (configDirectory == null) {
                return failed(Outcome.NO_CONFIG_DIRECTORY, "'" + agent.name() + "' does not say"
                        + " where it keeps its credentials, so nothing could be collected"
                        + " afterwards");
            }

            // Checked before anything is built: a second authorization is not free. Sokar does
            // not touch this machine's copy - the container has its own home - but whether a
            // second one invalidates the first is the provider's business, and somebody who
            // already has a working credential here almost certainly wants to copy it rather
            // than risk that. The same extractor 'vault import' uses answers the question.
            if (!force) {
                final Path onThisNode = VaultImportCommand.expand(configDirectory);
                if (Files.isDirectory(onThisNode)
                        && agent.extractCredential(onThisNode).isPresent()) {
                    return failed(Outcome.ALREADY_SIGNED_IN, "'" + agent.name() + "' is already"
                            + " signed in on this machine. Copy what it has with 'sokar vault"
                            + " import " + agent.name() + "', which logs in nowhere - or pass"
                            + " --force to authorize again, which may or may not invalidate the"
                            + " credential already here");
                }
            }

            final List<String> command = new ArrayList<>(List.of(agent.definition().binary()));
            command.addAll(loginArguments);
            out.println("agent     " + agent.name());
            out.println("runs      " + String.join(" ", command));
            out.println("where     a throwaway container with ordinary network access - no egress"
                    + " ruleset, no broker, no vault");
            if (dryRun) {
                out.println("previewed nothing was built or run");
                out.flush();
                return new Result(Outcome.PREVIEWED, "", "", 0, "");
            }
            out.flush();

            return run(context, agent, command, configDirectory, out);

        } catch (RuntimeException ex) {
            return failed(Outcome.FAILED, String.valueOf(ex.getMessage()));
        }
    }

    private static Result run(SokarContext context, InstalledAgent agent, List<String> command,
            String configDirectory, PrintWriter out) {

        final org.fuin.sokar.runtime.Podman podman = context.podman();
        final Project project = loginProject();

        org.fuin.sokar.runtime.ImageLayers layers = org.fuin.sokar.runtime.ImageLayers.none();
        layers = layers.and(browserShim(), java.util.List.of());
        layers = layers.and(agent.definition().installAsRoot(),
                org.fuin.sokar.agent.api.InstallScript.render(agent.definition().artifacts()));
        layers = layers.and(java.util.List.of(), agent.definition().installAsAgent());

        out.println("building  the login image - minutes the first time, seconds afterwards");
        out.println();
        out.flush();
        final String image = project.imageName();
        try {
            // On this terminal rather than through the runner, which collects the output and
            // hands it over at the end. A build says a great deal while it works and none of it
            // reached anybody: one line, then silence long enough to look like a hang.
            final int built = context.exec().applyAsInt(podman.buildArguments(project,
                    context.paths().buildContext("sokar-login"), layers,
                    org.fuin.sokar.runtime.Podman.Rebuild.CACHED));
            if (built != 0) {
                return failed(Outcome.FAILED,
                        "the login image could not be built - podman exited with " + built);
            }
        } catch (RuntimeException ex) {
            return failed(Outcome.FAILED, "the login image could not be built: " + ex.getMessage());
        }
        out.println();

        final String container = "sokar-login-" + System.currentTimeMillis();
        Path collected = null;
        try {
            // The node's own network, so a login that redirects to a port on localhost reaches
            // something. That is more access than a task ever gets, and it is why this container
            // is removed rather than kept: it exists for the seconds a login takes.
            final List<String> arguments = new ArrayList<>(List.of("run", "--interactive", "--tty",
                    "--network", "host", "--name", container, image));
            arguments.addAll(command);
            out.println();
            out.println("What follows is " + agent.name() + "'s own login, not Sokar's - it will"
                    + " ask you things, and Sokar takes over again when it exits.");
            out.println();
            out.println("Two things about doing it in here:");
            out.println();
            out.println("  * There is no browser in this container. Anything the agent tries to"
                    + " open prints the URL instead, marked with '=== Open this ... ==='. Open it"
                    + " on whatever machine you are sitting at.");
            out.println("  * Nothing is stored unless the login finishes. Ctrl-C or leaving the"
                    + " agent aborts it, and the container is removed either way.");
            out.println("  * If that machine is not this one, a redirect back to 'localhost' will"
                    + " not reach you. Open a second terminal and forward the port the URL names:");
            out.println();
            out.println("        ssh -L <port>:localhost:<port> " + hostname());
            out.println();
            out.println("    The container shares this machine's network, so the callback lands"
                    + " here and the forward carries it to your browser.");
            out.println();
            out.flush();
            final int code = context.exec().applyAsInt(podman.arguments(arguments));
            if (code != 0) {
                return failed(Outcome.NOTHING_TO_COLLECT,
                        "the login exited with " + code + ", so nothing was stored");
            }

            collected = Files.createTempDirectory("sokar-login");
            // Copied out rather than read in place: the extractor runs on this machine and knows
            // each agent's own file layout, so the credential is read by the same code 'vault
            // import' uses rather than by something written twice.
            podman.copyOut(container, inContainer(configDirectory), collected);

            final java.util.Optional<Credential> credential = agent.extractCredential(collected);
            if (credential.isEmpty()) {
                return failed(Outcome.NOTHING_TO_COLLECT, "the login left no credential in "
                        + configDirectory + " - it may have been cancelled");
            }
            final Credential value = credential.get();

            final SelectedProvider selection =
                    SelectedProvider.choose(context.providers(), agent.definition(), null);
            final String key = selection == null ? agent.name() : selection.name();

            final java.util.Optional<char[]> passphrase = new org.fuin.sokar.vault.PassphraseTiers(
                    org.fuin.sokar.vault.KernelKeyring.source(
                            context.paths().vaultKeyringKey()),
                    new org.fuin.sokar.vault.ConsolePassphrase("Vault passphrase: "))
                    .passphrase();
            if (passphrase.isEmpty()) {
                return failed(Outcome.VAULT_LOCKED, "logged in, but the vault is locked and"
                        + " nothing could ask for a passphrase - unlock it and run this again");
            }
            context.vault().update(passphrase.get(), entries -> {
                entries.put(key, new VaultEntry(value.secret(), value.type()));
                return entries;
            });
            return new Result(Outcome.STORED, key, value.type(), value.secret().length(), "");

        } catch (java.io.IOException | RuntimeException ex) {
            return failed(Outcome.FAILED, String.valueOf(ex.getMessage()));
        } finally {
            podman.remove(container);
            if (collected != null) {
                // The credential was in here. Removed whatever happened above.
                deleteTree(collected);
            }
        }
    }

    /**
     * Returns a declared config directory as it is inside the container.
     * <p>
     * <strong>Not {@code VaultImportCommand.expand}.</strong> That resolves {@code ~} against this
     * machine's home, which is right when reading a directory on the node and wrong here by one
     * user: the agent runs as {@code agent} inside the image, so {@code ~/.claude} is
     * {@code /home/agent/.claude} there and {@code /home/somebody/.claude} on the node. Copying
     * from the second would find nothing and report a login that produced no credential.
     *
     * @param declared The directory as the agent's manifest writes it.
     * @return The absolute path inside the container.
     */
    static String inContainer(String declared) {
        return declared.startsWith("~") ? AGENT_HOME + declared.substring(1) : declared;
    }

    /**
     * Returns this machine's name, for the forward somebody may have to set up.
     * <p>
     * Printed rather than left as {@code <this machine>}: whoever needs the command is already
     * doing something fiddly, and a line they can edit beats one they have to compose.
     *
     * @return The host name, or a placeholder when it cannot be read.
     */
    /**
     * Returns image lines installing something for the agent to "open a browser" with.
     * <p>
     * <strong>Without this the login stops dead.</strong> An agent asked to authenticate tries to
     * open a browser - through {@code xdg-open}, {@code sensible-browser} or {@code $BROWSER} -
     * and a container has none of them and no display. Measured: the attempt neither succeeds nor
     * reports anything, so the person is left at a prompt that never continues, in a container
     * they did not know how to leave.
     * <p>
     * The shim is not a browser and does not pretend to be one. It prints the URL, loudly, which
     * is exactly what somebody sitting at a different machine needs - and the same trick works
     * for any agent, because all three names are the standard ones.
     *
     * @return Lines to run as root while the login image is built.
     */
    static java.util.List<String> browserShim() {
        return java.util.List.of(
                "# There is no browser in here and no display. Anything trying to open one gets",
                "# this instead: it prints the URL rather than swallowing it, which is what",
                "# somebody at another machine actually needs.",
                "RUN printf '%s\\n' '#!/bin/sh' 'echo' "
                        + "'echo \"=== Open this in a browser on your own machine: ===\"' "
                        + "'echo \"$@\"' 'echo' > /usr/local/bin/xdg-open \\",
                "    && chmod 0755 /usr/local/bin/xdg-open \\",
                "    && ln -sf /usr/local/bin/xdg-open /usr/local/bin/sensible-browser \\",
                "    && ln -sf /usr/local/bin/xdg-open /usr/local/bin/www-browser",
                "ENV BROWSER=/usr/local/bin/xdg-open");
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "<this machine>";
        }
    }

    private static void deleteTree(Path directory) {
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // Best effort: what matters is that the vault has it, and a temporary
                    // directory that outlives this is a smaller problem than failing here.
                }
            });
        } catch (java.io.IOException ignored) {
            // Same.
        }
    }
}
