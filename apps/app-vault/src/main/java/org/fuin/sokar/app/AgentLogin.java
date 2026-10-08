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

        // First, and before anything expensive. A login container is never reused - the name
        // carries the millisecond it was made - so one still on the machine is litter, and the
        // teardown at the end cannot have covered every way a login ends: a kill leaves nothing
        // a chance to run. The login *image* is the expensive part and is deliberately kept.
        sweepLeftoverLogins(context.podman(), out);

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
                    context.paths().tasks().buildContext("sokar-login"), layers,
                    org.fuin.sokar.runtime.Podman.Rebuild.CACHED));
            if (built != 0) {
                return failed(Outcome.FAILED,
                        "the login image could not be built - podman exited with " + built);
            }
        } catch (RuntimeException ex) {
            return failed(Outcome.FAILED, "the login image could not be built: " + ex.getMessage());
        }
        out.println();

        final String container = org.fuin.sokar.runtime.ContainerName.login();
        Path collected = null;
        Path watched = null;
        Thread watcher = null;
        final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        // The finally below covers every ending except the usual one: a login somebody gives up
        // on is a Ctrl-C, and the signal reaches this process before the finally does. Reported
        // as containers left behind after an abandoned login.
        try (Teardown teardown = Teardown.arm(() -> podman.remove(container))) {
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
            // WATCHED WHILE IT RUNS, not collected after it stops. The token is written the
            // moment the login succeeds; everything after that - the agent's own session, how
            // somebody leaves it, whether the terminal is closed on it - is none of this
            // command's business and must not be able to lose it. The operator's point, and it
            // covers a case the exit code never could: an ssh session that dies takes this
            // process with it, and a credential collected only at the end dies with it too.
            final java.util.concurrent.atomic.AtomicReference<Credential> early =
                    new java.util.concurrent.atomic.AtomicReference<>();
            watched = Files.createTempDirectory("sokar-login-watch");
            final Path into = watched;
            watcher = Thread.ofVirtual().start(() -> watchFor(
                    context, podman, agent, container, configDirectory, into, early, ended, out));

            final int code = context.exec().applyAsInt(podman.arguments(arguments));
            // Let the watch finish before looking ourselves: a watch still asking the agent for the credential while
            // this asks it too made the second call fail now and then ("Call to ... ExtractCredential failed", the
            // suite, 2026-10-04), and a watch that found it a moment late is a login stored all the same.
            ended.set(true);
            settle(watcher);

            // LOOK FIRST, then judge the exit code. An agent whose login is "just run me" does
            // not stop when the login is done - it carries on into its own session, and a person
            // who has finished logging in leaves that session however they like. Ctrl-C is a
            // perfectly ordinary way out and gives 130, and treating that as "nothing was
            // stored" threw away a login that had already happened: measured on 2026-09-19 with
            // a real subscription, where the credential sat in the container and Sokar discarded
            // it over an exit code that says nothing about whether the login worked.
            collected = Files.createTempDirectory("sokar-login");
            // Copied out rather than read in place: the extractor runs on this machine and knows
            // each agent's own file layout, so the credential is read by the same code 'vault
            // import' uses rather than by something written twice.
            final java.util.Optional<Credential> credential;
            if (early.get() != null) {
                // Ended by the watcher the moment the login wrote it: the container is gone, and what it held is
                // what was found. Whatever the agent's full-screen view left on the terminal goes with it.
                credential = java.util.Optional.of(early.get());
                out.print("\033[?1049l\033[?25h\033[0m\033[r\033[?7h\033[?1000l\033[?1002l\033[?1003l\033[?1006l"
                        + "\033[H\033[2J");
                out.flush();
            } else {
                podman.copyOut(container, contentsOf(configDirectory), collected);
                credential = agent.extractCredential(collected);
            }
            if (credential.isEmpty()) {
                // BOTH paths: what the agent declares and where that actually was looked for.
                // "no credential in ~/.claude" reads as though this machine's home was searched,
                // and an hour went into asking which of the two it meant.
                final String where = configDirectory + " (" + inContainer(configDirectory)
                        + " in the container)";
                return failed(Outcome.NOTHING_TO_COLLECT, code == 0
                        ? "the login left no credential in " + where
                                + " - it may have been cancelled"
                        : "the login exited with " + code + " and left no credential in " + where
                                + " - it was cancelled or did not finish");
            }
            if (code != 0 && early.get() == null) {
                // Worth saying, and not worth refusing over: the credential is there.
                out.println("note      the agent exited with " + code + " - its own session,"
                        + " not the login, and the credential was written before that");
            }
            final Credential value = credential.get();

            final SelectedProvider selection =
                    SelectedProvider.choose(context.providers(), agent.definition(), null);
            final String key = selection == null ? agent.name() : selection.name();

            // Whatever already opens the vault - a cached passphrase or a device's share - and
            // only then a prompt. Storing a credential needs the vault open, not the passphrase.
            java.util.Optional<org.fuin.sokar.vault.VaultFile.Opener> way = context.opener();
            if (way.isEmpty()) {
                way = new org.fuin.sokar.vault.PassphraseTiers(
                        new org.fuin.sokar.vault.ConsolePassphrase("Vault passphrase: "))
                        .passphrase().map(org.fuin.sokar.vault.VaultFile.Opener::passphrase);
            }
            if (way.isEmpty()) {
                return failed(Outcome.VAULT_LOCKED, "logged in, but the vault is locked and"
                        + " nothing could ask for a passphrase - unlock it and run this again");
            }
            store(context, agent, value, way.get());
            return new Result(Outcome.STORED, key, value.type(), value.secret().length(), "");

        } catch (java.io.IOException | RuntimeException ex) {
            return failed(Outcome.FAILED, String.valueOf(ex.getMessage()));
        } finally {
            if (collected != null) {
                // The credential was in here. Removed whatever happened above.
                deleteTree(collected);
            }
            if (watched != null) {
                // So was every copy the watch made: the agent's whole config directory, its credential
                // among them. Left in /tmp, one directory per login stayed behind with it (found on the
                // test machine, 2026-10-04). The watch is let finish first, so it copies nothing in after.
                ended.set(true);
                if (watcher != null) {
                    settle(watcher);
                }
                deleteTree(watched);
            }
        }
    }

    /**
     * Removes login containers an earlier run left behind.
     * <p>
     * Reported from a machine carrying one thirteen hours old: it had outlived an interrupted
     * login from before the teardown existed. Sweeping here rather than only tearing down at the
     * end covers what a teardown structurally cannot - a {@code SIGKILL}, a power cut, or a
     * version that had no teardown at all.
     *
     * @param podman The runtime.
     * @param out Where a removal is reported.
     */
    private static void sweepLeftoverLogins(org.fuin.sokar.runtime.Podman podman, PrintWriter out) {
        try {
            final java.util.List<String> leftover = podman.sokarContainers().stream()
                    .filter(org.fuin.sokar.runtime.ContainerName::isLogin).toList();
            for (final String stale : leftover) {
                podman.remove(stale);
                out.println("removed   " + stale + ", left behind by an earlier login");
            }
        } catch (RuntimeException ex) {
            // A sweep that cannot run is not a reason to refuse a login. The worst case is the
            // container somebody already has staying where it is.
            out.println("note      could not check for leftover login containers: "
                    + ex.getMessage());
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
    /**
     * Puts a credential in the vault under the name a task will look for.
     * <p>
     * One method, because it is done from two moments now - the instant the login writes the
     * token, and the ordinary path after the run - and two of these would be two chances to
     * store it under different names.
     *
     * @param context The machine.
     * @param agent Whose credential it is.
     * @param value What was found.
     * @param way How the vault opens.
     */
    private static void store(SokarContext context, InstalledAgent agent, Credential value,
            org.fuin.sokar.vault.VaultFile.Opener way) {
        final SelectedProvider selection =
                SelectedProvider.choose(context.providers(), agent.definition(), null);
        final String key = selection == null ? agent.name() : selection.name();
        context.vault().update(way, entries -> {
            LoginCredentials.put(entries, key, value, true);
            return entries;
        });
    }

    /**
     * Watches the login container for the credential and stores it the moment it appears.
     * <p>
     * <strong>Why not wait for the process.</strong> An agent whose login is "just run me" writes
     * its token and then carries on into its own session. Waiting for that session to end makes
     * the token's survival depend on how somebody leaves it - and on this process outliving them,
     * which an ssh session closing does not guarantee. Watching makes the token safe the second it
     * exists.
     * <p>
     * <strong>It only stores when the vault is already open.</strong> Asking for a passphrase here
     * is impossible: the terminal belongs to the agent. When the vault is shut, the credential is
     * still captured and the ordinary path stores it after the run, where a prompt is possible.
     *
     * @param context The machine.
     * @param podman Runs the copy.
     * @param agent Whose credential this is.
     * @param container The login container.
     * @param configDirectory What the agent declares.
     * @param into A directory to copy into.
     * @param found Where to put what was found.
     * @param out Where to say it.
     */
    private static void watchFor(SokarContext context, org.fuin.sokar.runtime.Podman podman,
            InstalledAgent agent, String container, String configDirectory,
            java.nio.file.Path into, java.util.concurrent.atomic.AtomicReference<Credential> found,
            java.util.concurrent.atomic.AtomicBoolean ended, PrintWriter out) {
        while (!ended.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(java.time.Duration.ofSeconds(2));
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                podman.copyOut(container, contentsOf(configDirectory), into);
                final java.util.Optional<Credential> credential = agent.extractCredential(into);
                if (credential.isEmpty()) {
                    continue;
                }
                found.set(credential.get());
                // Stored here when it can be, so that nothing after this moment can lose it.
                final java.util.Optional<org.fuin.sokar.vault.VaultFile.Opener> way =
                        context.opener();
                if (way.isPresent()) {
                    store(context, agent, credential.get(), way.get());
                }
                // And then the login is over, said once, by Sokar. Left running, the agent carried on into its
                // own "Press Enter to continue" while Sokar said the person could leave it however they liked:
                // two voices for one moment (the operator, 2026-10-01). Nothing in the container is needed
                // any more; a shut vault is asked for after it, where a prompt is possible.
                podman.stop(container);
                return;
            } catch (final RuntimeException ex) {
                // The container may not be up yet, or the directory may not exist until the
                // login writes it. Neither is worth reporting: this is a watch, not a check.
                continue;
            }
        }
    }

    /**
     * Lets the watch end on its own: it looks at the flag between two looks, never in the middle of asking the agent.
     * <p>
     * Interrupted in the middle, a watch's call to the agent broke off while this asked the agent too, and that call
     * failed now and then ("Call to ... ExtractCredential failed", the suite, 2026-10-04). Interrupted only when it
     * has not ended after a while, so a hung agent cannot keep the login open.
     *
     * @param watcher The watch.
     */
    private static void settle(final Thread watcher) {
        try {
            if (!watcher.join(java.time.Duration.ofSeconds(15))) {
                watcher.interrupt();
                watcher.join(java.time.Duration.ofSeconds(5));
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static String inContainer(String declared) {
        return declared.startsWith("~") ? AGENT_HOME + declared.substring(1) : declared;
    }

    /**
     * Returns what to hand {@code podman cp} so the config directory's CONTENTS are copied.
     * <p>
     * <strong>This is why a login has never stored anything.</strong> {@code podman cp
     * container:/home/agent/.claude target} copies the directory itself, leaving
     * {@code target/.claude/.credentials.json} - while the extractor is handed {@code target} and
     * looks for {@code target/.credentials.json}, one level up from where the file is. So every
     * login found nothing, said "it may have been cancelled", and was believed.
     * <p>
     * The trailing {@code /.} is the difference, measured against podman rather than read: with
     * it, the contents land directly in the target. Nobody had measured a login end to end -
     * Agent Smith said so of his side, and it was true of mine.
     *
     * @param declared What the agent declares as its config directory.
     * @return The source for a copy, whose contents land in the target.
     */
    static String contentsOf(String declared) {
        return inContainer(declared) + "/.";
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
     * <p>
     * <strong>{@code BROWSER} is deliberately NOT set, and that is a reversal.</strong> It was,
     * and Agent Smith measured what it costs: with {@code BROWSER} set, Claude Code stops
     * printing a link and a code and switches to redirecting to {@code localhost} on a port it
     * picks per run - which on a machine somebody reaches over ssh lands on the wrong computer
     * and needs that port forwarded, with the port knowable only by reading it out of the URL.
     * Unset, the login stays link-and-code: the page opens where the person is, they paste the
     * code back into the terminal, and nothing has to be forwarded at all.
     * <p>
     * The shim stays, because an agent that calls {@code xdg-open} itself still needs the address
     * printed rather than swallowed. <strong>What is NOT measured</strong> is whether the shim's
     * mere presence makes an agent choose the redirect anyway; Agent Smith's measurement varied
     * {@code BROWSER} and not the shim.
     *
     * @return Lines to run as root while the login image is built.
     */
    static java.util.List<String> browserShim() {
        final java.util.List<String> script = java.util.List.of(
                "#!/bin/sh",
                "# Opens nothing. Writes the address where the person can see it, and says which",
                "# port the login will answer on, so their own machine can forward it.",
                "url=$1",
                "rest=${url#*localhost%3A}",
                "if [ \"$rest\" = \"$url\" ]; then rest=${url#*localhost:}; fi",
                "if [ \"$rest\" = \"$url\" ]; then port=; else port=${rest%%[!0-9]*}; fi",
                "printf \"\\n=== Open this in a browser on your own machine: ===\\n\"",
                "# OSC 8, so a terminal that renders hyperlinks offers it with a press. The plain",
                "# text is printed too, for a terminal that does not.",
                "# BEL rather than ESC-backslash to close them: both are valid, the first needs",
                "# no backslash at all, and a backslash through a shell inside a RUN line inside",
                "# a Java string is where this went wrong the first time - it printed a literal",
                "# n where a newline belonged.",
                "# id=sokar-login marks it as OURS. A program may print links of its own - an",
                "# agent that also offers a code flow prints one - and a client that cannot tell",
                "# them apart can only guess which to put first. The mark says this is the page",
                "# the login is waiting on, the same way the forward marker says the port.",
                "printf \"\\033]8;id=sokar-login;%s\\007%s\\033]8;;\\007\\n\" \"$url\" \"$url\"",
                "# And the port, said rather than left to be read out of the query string.",
                "if [ -n \"$port\" ]; then printf \"\\033]5379;forward;%s\\007\" \"$port\"; fi",
                "if [ -n \"$port\" ]; then",
                "  printf \"    it will answer on localhost:%s of the machine\\n\\n\" \"$port\"",
                "fi");
        final String encoded = java.util.Base64.getEncoder().encodeToString(
                String.join("\n", script).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("# There is no browser in here and no display. Anything trying to open one gets");
        lines.add("# this instead. It is written base64 because the quoting of the escape");
        lines.add("# sequences through a RUN line is unreadable either way, and a blob with the");
        lines.add("# script printed above it is honest about what it is. Line by line:");
        for (final String line : script) {
            lines.add("#   " + line);
        }
        lines.add("RUN echo " + encoded + " | base64 -d > /usr/local/bin/xdg-open \\");
        lines.add("    && chmod 0755 /usr/local/bin/xdg-open \\");
        lines.add("    && ln -sf /usr/local/bin/xdg-open /usr/local/bin/sensible-browser \\");
        lines.add("    && ln -sf /usr/local/bin/xdg-open /usr/local/bin/www-browser");
        // Set again, deliberately. Unset, an agent stays on link-and-code and nothing has to be
        // forwarded; set, it redirects to a port on this machine's own loopback - which the
        // login container shares, so the operator's ssh forward reaches it. The operator chose
        // the second: it is the one where nobody has to copy a code between two windows.
        lines.add("ENV BROWSER=/usr/local/bin/xdg-open");
        return java.util.List.copyOf(lines);
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
