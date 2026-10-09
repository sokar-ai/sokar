package org.fuin.sokar.runtime;

import org.fuin.sokar.wire.Sidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.project.Project;

/**
 * The subset of podman Sokar drives.
 * <p>
 * The command line is used rather than the REST API. It is the interface podman documents for
 * humans and keeps working across versions, it needs no socket to be running, and it behaves the
 * same rootless as rooted. The cost is parsing output, which is why every query asks for an
 * explicit format instead of scraping the default table.
 */
public class Podman {

    private final CommandRunner runner;

    private final java.util.function.UnaryOperator<String> environment;

    private final String executable;

    private final @Nullable Path networkConfiguration;

    /**
     * Constructor using the {@code podman} on the path.
     *
     * @param runner Runs the commands.
     */
    public Podman(CommandRunner runner) {
        this(runner, "podman");
    }

    /**
     * Constructor with an explicit executable.
     *
     * @param runner Runs the commands.
     * @param executable Program name or path.
     */
    public Podman(CommandRunner runner, String executable) {
        this(runner, executable, null);
    }

    /**
     * Constructor with the network configuration Sokar's own containers are started with.
     *
     * @param runner Runs the commands.
     * @param executable Program name or path.
     * @param networkConfiguration File {@link LoopbackMapping} is written to, or {@code null} to
     *        start containers with podman's configuration untouched.
     */
    public Podman(CommandRunner runner, String executable,
            @Nullable Path networkConfiguration) {
        this(runner, executable, networkConfiguration, System::getenv);
    }

    /**
     * Constructor with the environment to read the operator's terminal from.
     *
     * @param runner Runs the commands.
     * @param executable Program name or path.
     * @param networkConfiguration File {@link LoopbackMapping} is written to, or {@code null}.
     * @param environment Reads an environment variable, for a test that has its own.
     */
    public Podman(CommandRunner runner, String executable,
            @Nullable Path networkConfiguration,
            java.util.function.UnaryOperator<String> environment) {
        this(runner, executable, networkConfiguration, environment, null);
    }

    private Podman(CommandRunner runner, String executable, @Nullable Path networkConfiguration,
            java.util.function.UnaryOperator<String> environment,
            java.util.function.@Nullable Function<String, @Nullable String> recorded) {
        this.runner = runner;
        this.executable = executable;
        this.networkConfiguration = networkConfiguration;
        this.environment = environment;
        this.recorded = recorded;
    }

    /** Each task's name to the container id Sokar recorded when it made it, or {@code null}; absent, nothing is checked. */
    private final java.util.function.@Nullable Function<String, @Nullable String> recorded;

    /**
     * Returns this runtime acting only on the tasks Sokar recorded, each by the container id it recorded.
     * <p>
     * <strong>A task is what Sokar made, never what is named like one</strong> (the operator, 2026-10-04): anything
     * that runs as the account can start a container with any name and any label. So a task is listed only when its
     * live id is the one Sokar recorded when it created it, and every act on a task - start, stop, remove, exec, its
     * screen, a line typed into it - goes to that id, never to the name. A name with no record is acted on by nothing.
     *
     * @param records Each task's name to the id recorded for it, or {@code null} for none.
     * @return The runtime that checks.
     */
    public Podman recognizing(java.util.function.Function<String, @Nullable String> records) {
        return new Podman(runner, executable, networkConfiguration, environment, records);
    }

    /**
     * Returns what to hand podman for a container: for a task, the id Sokar recorded for it.
     *
     * @param container A container name.
     * @return The id recorded for a task; the name itself for anything else, or where nothing is recorded at all.
     * @throws NotATaskException For a task name Sokar has no record of.
     */
    String target(String container) {
        if (recorded == null || !ContainerName.isTask(container)) {
            return container;
        }
        final String id = recorded.apply(container);
        if (id == null || id.isBlank()) {
            throw new NotATaskException(container);
        }
        return id;
    }

    /** What a terminal that cannot do anything calls itself; never worth passing on. */
    private static final String NOTHING = "dumb";

    /** The safe 256-colour entry, present wherever ncurses' base terminfo set is. */
    static final String FALLBACK_TERMINAL = "xterm-256color";

    /** Resolved once per container: attaching twice must not ask twice. */
    private final java.util.Map<String, java.util.List<String>> terminalEnvironment =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns the {@code --env} arguments that tell a container what terminal it is on.
     * <p>
     * <strong>podman passes no environment through, and invents {@code TERM=xterm}.</strong>
     * Measured on 2026-09-12: {@code podman exec -t} sets that and nothing else, whatever the
     * operator's own terminal is. Everything inside then renders in eight colours, and an agent's
     * interface is the thing that suffers most.
     * <p>
     * <strong>The operator's own value is passed only when the container can resolve it.</strong>
     * The base image carries the ncurses base set - {@code xterm-256color}, {@code screen-256color}
     * and {@code tmux-256color} are there - and nothing else: no {@code alacritty}, no
     * {@code xterm-kitty}, no {@code foot}. Sending one of those names to a container that has
     * never heard of it is worse than saying nothing, because a program that cannot look its
     * terminal up falls back further than {@code xterm} would have. So the container is asked
     * first, and {@link #FALLBACK_TERMINAL} is what an unknown name becomes - a superset for
     * colour, and compatible in practice with every terminal that would have reported one.
     * <p>
     * {@code COLORTERM} rides along when it is set. It names no terminfo entry, so nothing can
     * fail to resolve it, and it is how a program decides it may emit 24-bit colour at all.
     *
     * <strong>This asks the container, so it is not part of building an argument list.</strong>
     * The builders below are pure on purpose - what they return is handed straight to {@code exec}
     * - and a builder that ran a command would make every caller pay for a round trip whether it
     * was attaching a person's terminal or not. The callers that are pass the result in.
     *
     * @param container Container about to be attached to.
     * @return Arguments to insert, possibly empty.
     */
    public List<String> terminalFor(String container) {
        return terminalEnvironment.computeIfAbsent(container, name -> {
            final List<String> arguments = new ArrayList<>();
            final String wanted = environment.apply("TERM");
            if (wanted != null && !wanted.isBlank() && !NOTHING.equals(wanted)) {
                final String usable = knows(name, wanted) ? wanted
                        : knows(name, FALLBACK_TERMINAL) ? FALLBACK_TERMINAL : null;
                if (usable != null) {
                    arguments.add("--env");
                    arguments.add("TERM=" + usable);
                }
            }
            final String colour = environment.apply("COLORTERM");
            if (colour != null && !colour.isBlank()) {
                arguments.add("--env");
                arguments.add("COLORTERM=" + colour);
            }
            return List.copyOf(arguments);
        });
    }

    /**
     * Asks a container whether it can look a terminal name up.
     *
     * @param container Container name.
     * @param terminal Terminal name.
     * @return Whether {@code infocmp} resolves it there.
     */
    private boolean knows(String container, String terminal) {
        try {
            return runner.run(podman("exec", target(container), "infocmp", terminal)).exitCode() == 0;
        } catch (RuntimeException ex) {
            // A container that cannot be asked is one that cannot be attached to either, and the
            // attach itself is about to say so far better than this could.
            return false;
        }
    }

    private Command podman(String... arguments) {
        final List<String> all = new ArrayList<>();
        all.add(executable);
        all.addAll(List.of(arguments));
        return Command.of(all);
    }

    /**
     * Says how a podman call failed: its exit code and the first line it wrote to standard error.
     * <p>
     * That line is the one an operator can act on; a report that says only "failed" drops it.
     *
     * @param result The failed call.
     * @return For example {@code exit 125: Error: cannot set up namespace}.
     */
    private static String failure(CommandResult result) {
        final String first = result.standardError().lines().map(String::strip)
                .filter(line -> !line.isEmpty()).findFirst().orElse("");
        if (first.isEmpty()) {
            return "exit " + result.exitCode() + ", nothing on standard error";
        }
        return "exit " + result.exitCode() + ": "
                + (first.length() > 200 ? first.substring(0, 200) + "..." : first);
    }

    /** Oldest podman Sokar runs on. */
    public static final int MINIMUM_MAJOR = 5;

    /**
     * Returns the podman version.
     *
     * @return Version string such as {@code 5.7.0}.
     */
    public String version() {
        return runner.runOrFail(podman("version", "--format", "{{.Client.Version}}")).trimmedOutput();
    }

    /**
     * Tells whether this podman is one Sokar supports.
     * <p>
     * podman 4 is refused rather than run with less than the guarantees Sokar describes. It has no
     * pasta, so the host's loopback cannot be mapped into a container and the git gate has to bind
     * every interface - the endpoint a task pushes to then sits on the operator's network, held
     * shut by the per-task token alone. Carrying two networking paths, only one of which keeps the
     * property the documentation states, is worse than naming the version this needs. Ubuntu 24.04
     * LTS ships 4.9.3, so this is a real refusal and not a theoretical one.
     *
     * @return Empty when the version is supported, otherwise what to say about it.
     */
    public Optional<String> unsupportedVersion() {
        final CommandResult result =
                runner.run(podman("version", "--format", "{{.Client.Version}}"));
        if (!result.successful()) {
            return Optional.of("podman fails 'podman version' (" + failure(result)
                    + "); Sokar needs podman " + MINIMUM_MAJOR + " or newer");
        }
        final String reported = result.trimmedOutput();
        final int dot = reported.indexOf('.');
        try {
            if (Integer.parseInt(dot < 0 ? reported : reported.substring(0, dot))
                    >= MINIMUM_MAJOR) {
                return Optional.empty();
            }
        } catch (NumberFormatException ex) {
            // A version that cannot be read is not a version that can be trusted to be new
            // enough, and saying which one was found is what makes that reviewable.
            return Optional.of("podman reports '" + reported + "', which is not a version Sokar"
                    + " can read; it needs podman " + MINIMUM_MAJOR + " or newer");
        }
        return Optional.of("podman " + reported + " is too old; Sokar needs podman "
                + MINIMUM_MAJOR + " or newer, because older ones cannot map this host's loopback"
                + " into a container and the git gate would sit on your local network");
    }

    /**
     * Returns the address a container reaches this host at.
     * <p>
     * Asked rather than assumed, because podman decides it and the answer differs by version:
     * measured on 2026-09-05, podman 5.8.1 gives {@code 169.254.1.2} and podman 4.9.3 gives the
     * host's own LAN address. Ubuntu 24.04 LTS ships 4.9.3, so a hardcoded constant firewalled
     * the git gate off for everyone on that release, with a push that hung until it timed out as
     * the only symptom.
     * <p>
     * It has to be a throwaway container rather than a query: the name is written into a
     * container's {@code /etc/hosts}, and podman fills that in only once the container starts -
     * after {@code create} there is not even a file. The image is one the caller already needs,
     * so nothing extra is pulled.
     *
     * @param image Image to ask with, which must already be present.
     * @return The address, or empty when it cannot be determined.
     */
    public Optional<String> hostAddressFromContainer(String image) {
        final CommandResult result = runner.run(podman("run", "--rm", image,
                "cat", "/etc/hosts"));
        if (!result.successful()) {
            return Optional.empty();
        }
        for (final String line : result.trimmedOutput().split("\n")) {
            final String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            final String[] fields = entry.split("\\s+");
            for (int i = 1; i < fields.length; i++) {
                if (ContainerSpec.HOST_FROM_CONTAINER.equals(fields[i])) {
                    return Optional.of(fields[0]);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Tells whether an image is present locally.
     *
     * @param image Image name, with or without a tag.
     * @return {@code true} if podman knows the image.
     */
    public boolean hasImage(String image) {
        return runner.run(podman("image", "exists", image)).successful();
    }

    /**
     * Builds the task image for a project.
     *
     * @param project The project.
     * @param contextDirectory Directory the Containerfile is written into and built from.
     * @return Name of the built image.
     */
    public String buildImage(Project project, Path contextDirectory) {
        return buildImage(project, contextDirectory, ImageLayers.none());
    }

    /**
     * Builds the task image for a project, including layers contributed from outside.
     *
     * @param project The project.
     * @param contextDirectory Directory the Containerfile is written into and built from.
     * @param layers What the agent and the project contribute.
     * @return Name of the built image.
     */
    public String buildImage(Project project, Path contextDirectory, ImageLayers layers) {
        final Path containerfile = contextDirectory.resolve("Containerfile");
        try {
            Files.createDirectories(contextDirectory);
            Files.writeString(containerfile, Containerfile.render(project, layers),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ContainerException("Cannot write " + containerfile, ex);
        }
        return buildImage(project, contextDirectory, layers, Rebuild.CACHED);
    }

    /** How much of a previous build to discard. */
    public enum Rebuild {

        /**
         * Whatever podman's cache still considers valid is reused.
         * <p>
         * What every task start does. Not "skip the build": the build runs and the cache decides
         * layer by layer, so an edited project file rebuilds what it changed and nothing else.
         */
        CACHED,

        /**
         * The agent's tooling is rebuilt; the base image and its packages are kept.
         * <p>
         * Done by passing a value podman has not seen for the argument that marks where the
         * agent's layers begin, which invalidates the cache from that line down. There is no
         * podman flag for "rebuild from here", so this is a mechanism rather than an option.
         */
        AGENT,

        /** Nothing is reused. Downloads the base image's packages again. */
        EVERYTHING
    }

    /**
     * Builds a project's task image, discarding as much of the previous build as asked.
     *
     * @param project The project.
     * @param contextDirectory Where the Containerfile is written.
     * @param layers What the agent and the project contribute.
     * @param rebuild How much to discard.
     * @return The image name.
     */
    public String buildImage(Project project, Path contextDirectory, ImageLayers layers,
            Rebuild rebuild) {
        runner.runOrFail(Command.of(buildArguments(project, contextDirectory, layers, rebuild)));
        return project.imageName();
    }

    /**
     * Writes the Containerfile and returns the command that would build it.
     * <p>
     * Exists so a caller that has a terminal can run the build on it rather than through the
     * runner, which collects output and hands it over at the end. A build takes minutes and says
     * a great deal while it works - which layer is cached, what is being fetched - and none of
     * that reached anybody: the only line was Sokar's own "building", followed by silence long
     * enough to look like a hang.
     *
     * @param project The project.
     * @param contextDirectory Where the Containerfile is written.
     * @param layers What the agent and the project contribute.
     * @param rebuild How much of any previous build to discard.
     * @return The executable and its arguments.
     */
    public List<String> buildArguments(Project project, Path contextDirectory, ImageLayers layers,
            Rebuild rebuild) {
        final Path containerfile = contextDirectory.resolve("Containerfile");
        try {
            Files.createDirectories(contextDirectory);
            Files.writeString(containerfile, Containerfile.render(project, layers),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ContainerException("Cannot write " + containerfile, ex);
        }
        final List<String> arguments = new ArrayList<>(List.of("build",
                "--tag", project.imageName(), "--file", containerfile.toString()));
        if (rebuild == Rebuild.EVERYTHING) {
            arguments.add("--no-cache");
        } else if (rebuild == Rebuild.AGENT) {
            // A value podman has not seen before. The clock is enough: this only has to differ
            // from whatever the last build used, and nothing in the image reads it.
            arguments.add("--build-arg");
            arguments.add(Containerfile.LAYER_EPOCH + "="
                    + java.time.Instant.now().toEpochMilli());
        }
        arguments.add(contextDirectory.toString());
        return arguments(arguments);
    }

    /**
     * Creates a container without starting it.
     *
     * @param specification What to create.
     * @return Container id.
     */
    public String create(ContainerSpec specification) {
        final List<String> arguments = new ArrayList<>(List.of("create"));
        arguments.addAll(specification.toArguments());
        // The arguments name the variables; the values travel in this process's environment, where
        // they are not world-readable. The two halves come from the same object so neither can be
        // sent without the other.
        return runner.runOrFail(podman(arguments.toArray(String[]::new))
                .withEnvironment(specification.environment())).trimmedOutput();
    }

    /**
     * Starts a created container.
     * <p>
     * This is where {@link LoopbackMapping} has to be applied - podman builds the pasta command
     * line here and nowhere else - and it is applied to every start rather than to the one in
     * {@code task start}, so that a task started again later comes up with the same networking as one
     * that never stopped. A gate the container cannot reach only shows up as a push that hangs.
     *
     * @param container Container name or id.
     */
    public void start(String container) {
        // In a scope of its own: 'podman start' leaves the container's monitor (conmon), its network
        // and whatever the hooks start in the caller's control group, and a monitor that dies takes the
        // container with it - measured, when the caller was the daemon and the daemon was stopped.
        final Command start = withNetworkConfiguration(podman("start", target(container)));
        runner.runOrFail(new Command(org.fuin.sokar.core.process.Scope.around("sokar " + container, start.arguments()),
                start.workingDirectory(), start.environment(), start.input()));
    }

    /**
     * Returns the command with Sokar's own network configuration in its environment, writing that
     * configuration first.
     * <p>
     * Written on every start rather than once at setup: the file belongs to this build of Sokar,
     * and an operator who upgraded would otherwise keep whatever the older one left behind.
     *
     * @param command Command to run.
     * @return The command, unchanged when there is no configuration to apply.
     */
    private Command withNetworkConfiguration(Command command) {
        if (networkConfiguration == null) {
            return command;
        }
        LoopbackMapping.write(networkConfiguration);
        return new Command(command.arguments(), command.workingDirectory(),
                Map.of(LoopbackMapping.VARIABLE, networkConfiguration.toString()),
                command.input());
    }

    /**
     * Returns how podman connects a rootless container to the network.
     * <p>
     * Asked rather than assumed, because it decides whether the git gate can bind loopback only:
     * {@link LoopbackMapping} is a pasta option, and podman ignores it under slirp4netns without
     * saying so.
     *
     * @return {@code pasta} or {@code slirp4netns}, or empty when podman cannot be asked.
     */
    public Optional<String> rootlessNetworkCmd() {
        final CommandResult result =
                runner.run(podman("info", "--format", "{{.Host.RootlessNetworkCmd}}"));
        if (!result.successful() || result.trimmedOutput().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.trimmedOutput());
    }

    /**
     * Returns how many paths the container has that its image does not.
     * <p>
     * What an agent installs inside a container - packages, a toolchain, a cache it warmed - is
     * destroyed with the container and has nowhere to arrive, unlike the workspace, which the gate
     * holds. Nothing else records it, so removal is the last moment it can be counted.
     * <p>
     * Added paths only. {@code podman diff} also reports a directory as changed the moment
     * anything under it moves, which is true of {@code /etc} and {@code /var} on any container
     * that ran at all, and counting those would put a number in front of an operator that is never
     * zero and therefore means nothing.
     *
     * @param container Container name or id.
     * @return How many paths were added, or zero when podman cannot say.
     */
    public long addedPaths(String container) {
        final CommandResult result = runner.run(podman("diff", target(container)));
        if (!result.successful()) {
            // Not an error: this is a nicety on the way to removing something, and a container
            // that is already gone answers nothing.
            return 0;
        }
        return result.standardOutput().lines().filter(line -> line.startsWith("A ")).count();
    }

    /**
     * Returns the id of a container, if it exists.
     *
     * @param container Container name.
     * @return Container id, or empty.
     */
    public Optional<String> idOf(String container) {
        final String target;
        try {
            target = target(container);
        } catch (final NotATaskException ex) {
            // A task Sokar has no record of does not exist as one: thrown, it replaced the refusal of a start that made
            // nothing and asked here while cleaning up.
            return Optional.empty();
        }
        final CommandResult result = runner.run(podman("container", "inspect",
                "--format", "{{.Id}}", target));
        return result.successful() && !result.trimmedOutput().isEmpty()
                ? Optional.of(result.trimmedOutput())
                : Optional.empty();
    }

    /**
     * Returns the host process id of a running container's init process.
     *
     * @param container Container name or id.
     * @return Process id, or empty if the container is not running.
     */
    public Optional<Long> pidOf(String container) {
        final CommandResult result = runner.run(podman("container", "inspect",
                "--format", "{{.State.Pid}}", target(container)));
        if (!result.successful()) {
            return Optional.empty();
        }
        try {
            final long pid = Long.parseLong(result.trimmedOutput());
            return pid > 0 ? Optional.of(pid) : Optional.empty();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Stops a container without removing it.
     * <p>
     * A stopped container keeps its filesystem, so the task's workspace and everything the agent
     * did in it survive and can be resumed. Removing it is a separate decision.
     *
     * @param container Container name or id.
     */
    public void stop(String container) {
        runner.run(podman("stop", "--time", "5", target(container)));
    }

    /**
     * Stops a container, then removes it. Neither step fails the caller: this runs on the way out,
     * where a container that is already gone is the desired state, not a problem.
     *
     * @param container Container name or id.
     */
    public void remove(String container) {
        final String id = target(container);
        runner.run(podman("stop", "--time", "5", id));
        runner.run(podman("rm", "--force", id));
    }

    /**
     * Lists the names of all containers Sokar created.
     *
     * @return Container names, running or not.
     */
    public List<String> sokarContainers() {
        return runner.runOrFail(podman("ps", "--all", "--format", "{{.Names}}"))
                .standardOutput().lines()
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .filter(ContainerName::isSokar)
                .toList();
    }

    /**
     * Removes a project image, if it is there.
     * <p>
     * An image that is not there is the outcome asked for, not a failure - the same rule as
     * removing a container. A project whose image was never built, or was pruned, must not make a
     * deletion look as though it went wrong.
     *
     * @param image Image name.
     * @return {@code true} if podman removed something.
     */
    public boolean removeImage(String image) {
        return runner.run(podman("rmi", "--force", image)).successful();
    }

    /** What podman puts before the name of an image built on this machine. */
    private static final String LOCAL = "localhost/";

    /**
     * Lists the project images Sokar has built, by name.
     * <p>
     * One call rather than {@link #hasImage} per project. Anything reporting on every project asks
     * this once: a subprocess per project is a cost nothing on screen explains, and the interface
     * re-asks for the project list after every task start and every approval.
     *
     * @return Image names, as {@code sokar/<project>}, possibly empty.
     */
    public Set<String> sokarImages() {
        return runner.run(podman("images", "--format", "{{.Repository}}"))
                .standardOutput().lines()
                .map(String::strip)
                // podman names an image it built here 'localhost/sokar/<project>'. Kept only when it began 'sokar/', not
                // one was found: every project read as never prepared, and an unfollow never named its image.
                .map(name -> name.startsWith(LOCAL) ? name.substring(LOCAL.length()) : name)
                .filter(name -> name.startsWith("sokar/"))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * Runs a short command in a container and returns what it printed.
     * <p>
     * For the small questions - what does the workspace look like, what is this variable set to -
     * where the answer is the point and it fits in memory. {@link #execute} is for an agent run,
     * whose output is measured in megabytes and belongs in a file.
     *
     * @param container Container name or id.
     * @param environment Extra variables for this command only.
     * @param command Program and arguments.
     * @return The result, successful or not.
     */
    public CommandResult ask(String container, Map<String, String> environment,
            List<String> command) {
        final List<String> arguments = new ArrayList<>(List.of("exec"));
        arguments.addAll(ContainerSpec.passedThrough(environment.keySet()));
        arguments.add(target(container));
        arguments.addAll(command);
        return runner.run(podman(arguments.toArray(new String[0])).withEnvironment(environment));
    }

    /**
     * Returns the image a container was created from, as name and id.
     * <p>
     * Both are needed to tell an operator that a task is running something older than the project's
     * current image: the name is what was asked for, and the id is what it actually resolved to
     * when the container was made.
     *
     * @param container Container name or id.
     * @return {@code name} and {@code id}, or empty if the container is unknown.
     */
    public Optional<String[]> imageOf(String container) {
        final CommandResult result = runner.run(podman("container", "inspect",
                "--format", "{{.ImageName}}\t{{.Image}}", target(container)));
        if (!result.successful()) {
            return Optional.empty();
        }
        final String[] parts = result.trimmedOutput().split("\t", 2);
        return parts.length == 2 && !parts[0].isBlank() ? Optional.of(parts) : Optional.empty();
    }

    /**
     * Returns the id an image name resolves to now.
     *
     * @param image Image name or tag.
     * @return Image id, or empty if there is no such image.
     */
    public Optional<String> imageId(String image) {
        final CommandResult result = runner.run(podman("image", "inspect",
                "--format", "{{.Id}}", image));
        return result.successful() && !result.trimmedOutput().isBlank()
                ? Optional.of(result.trimmedOutput())
                : Optional.empty();
    }

    /**
     * Returns what an image records about the recipe it was built from.
     * <p>
     * Empty when there is no such image, and also when the image predates the label - an older
     * image cannot be called stale on the strength of a label nobody wrote, so it reads as built
     * and unknown rather than as out of date.
     *
     * @param image Image name or tag.
     * @return The recipe fingerprint, or empty.
     */
    public Optional<String> imageRecipe(String image) {
        final CommandResult result = runner.run(podman("image", "inspect", "--format",
                "{{index .Labels \"org.fuin.sokar.recipe\"}}", image));
        if (!result.successful()) {
            return Optional.empty();
        }
        final String label = result.trimmedOutput();
        // podman prints "<no value>" for a label an image does not carry.
        return label.isBlank() || label.startsWith("<") ? Optional.empty() : Optional.of(label);
    }

    /**
     * Lists Sokar's containers with the state the runtime reports for each.
     *
     * @return Summaries, running or not, in the runtime's own order.
     */
    public List<ContainerSummary> sokarTasks() {
        // A tab rather than a space: the state is a phrase ("Up 4 minutes"), so anything the
        // state itself can contain cannot be the separator.
        // Each label asked for by name. '{{.Labels}}' renders Go's own map formatting -
        // 'map[a:b c:d]', space separated, colon separated - not the 'k=v,k=v' this first
        // assumed. Measured against podman 5.7.0 after every task listed no project: the parser
        // and the test agreed with each other and with nothing else. 'index' answers the one
        // value, and empty when the container carries no such label, so there is no format to
        // get wrong.
        return runner.runOrFail(podman("ps", "--all", "--no-trunc", "--format",
                        "{{.Names}}\t{{.Status}}\t{{.StartedAt}}\t{{.ExitedAt}}"
                        + "\t{{index .Labels \"" + Sidecar.PROJECT_LABEL + "\"}}"
                        + "\t{{index .Labels \"" + Sidecar.CLASS_LABEL + "\"}}"
                        + "\t{{index .Labels \"" + Sidecar.REPOSITORY_LABEL + "\"}}"
                        + "\t{{index .Labels \"" + Sidecar.COMMIT_LABEL + "\"}}\t{{.ID}}"))
                .standardOutput().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split("\t", 9))
                .filter(parts -> ContainerName.isTask(parts[0]))
                // Only what Sokar made: the live id is the one it recorded at create.
                .filter(parts -> recorded == null || parts.length > 8 && !parts[8].isBlank()
                        && parts[8].strip().equals(recorded.apply(parts[0])))
                .map(parts -> new ContainerSummary(parts[0], parts.length > 1 ? parts[1] : "",
                        since(parts),
                        label(parts, 4), label(parts, 5), label(parts, 6), label(parts, 7)))
                .toList();
    }

    /**
     * Returns one label from a {@code ps} line, or {@code null} when it carries none.
     * <p>
     * The field is the label's value, because the format asked for it by name rather than for the
     * whole map. Empty means the container has no such label - a container created before Sokar
     * wrote them, which the sidecar still answers for.
     *
     * @param parts The ps line, split.
     * @param field Which field the label was asked for in.
     * @return The value, or {@code null}.
     */
    @org.jspecify.annotations.Nullable
    private static String label(String[] parts, int field) {
        if (parts.length <= field) {
            return null;
        }
        final String value = parts[field].strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * Returns when the container's current state began, as an instant.
     * <p>
     * The state that matters is the current one: a running container has been up since it started,
     * a stopped one has been down since it exited. Both come from {@code ps} rather than from an
     * inspect per task, which would be one call per row of a list an interface redraws.
     * <p>
     * <strong>Both fields can be nonsense.</strong> A container that was created and never started
     * answers {@code -62135596800} - Go's zero time, the year 1 - and podman renders it as
     * "Exited (0) 292 years ago". Measured on a machine with one such container left over from a
     * failed start. Anything at or below zero is treated as "the runtime did not say".
     *
     * @param parts The ps line, split.
     * @return ISO-8601 instant, or empty.
     */
    private static String since(String[] parts) {
        final boolean up = parts.length > 1 && parts[1].startsWith("Up");
        final int field = up ? 2 : 3;
        if (parts.length <= field) {
            return "";
        }
        try {
            final long seconds = Long.parseLong(parts[field].strip());
            return seconds <= 0 ? "" : java.time.Instant.ofEpochSecond(seconds).toString();
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    /**
     * Runs a command inside a running container and writes its output to a file.
     * <p>
     * Output goes to a file rather than being collected in memory: an agent run produces a great
     * deal of it, and the file is also what the agent's own formatter is later asked to render.
     *
     * @param container Container name or id.
     * @param environment Extra variables for this command only.
     * @param command Program and arguments.
     * @param output File to write standard output and error into.
     * @param timeout How long the command may run.
     * @return Exit code of the command.
     * @throws ContainerException If the command cannot be started.
     */
    /**
     * Returns the argument list an unattended run is started with.
     * <p>
     * Separate so it can be read without starting anything, which is the only way to assert what
     * it does NOT contain.
     *
     * @param container Container name or id.
     * @param environment Extra variables for this command only.
     * @param command Program and arguments.
     * @return Arguments.
     */
    List<String> executeArguments(String container, Map<String, String> environment,
            List<String> command) {
        final List<String> arguments = new ArrayList<>(List.of(executable, "exec"));
        arguments.addAll(ContainerSpec.passedThrough(environment.keySet()));
        arguments.add(target(container));
        arguments.addAll(command);
        return List.copyOf(arguments);
    }

    public int execute(String container, Map<String, String> environment, List<String> command,
            Path output, Duration timeout) {

        // NO --interactive, and that is load-bearing rather than an omission. Measured on
        // 2026-09-12: pi and omp block at startup when stdin is an open pipe - omp sat in
        // 'readPipedInput' past 150 s, pi produced nothing for 180 s - and both run normally with
        // stdin closed. Without '-i' podman attaches nothing to the container's stdin, so the
        // agent sees it closed. Adding the flag here would hang an unattended run forever, and it
        // would look like a task that is WORKING, because the agent's own "still starting" lines
        // keep the log growing. A test asserts this flag stays absent.
        final List<String> arguments = executeArguments(container, environment, command);

        try {
            final ProcessBuilder builder = new ProcessBuilder(arguments)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            // Where the values go, since the arguments above carry only the names.
            builder.environment().putAll(environment);
            final Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ContainerException("The agent did not finish within "
                        + timeout.toSeconds() + " seconds");
            }
            return process.exitValue();
        } catch (IOException ex) {
            throw new ContainerException("Cannot run the agent in " + container, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ContainerException("Interrupted while running the agent", ex);
        }
    }

    /**
     * Returns the arguments that write a file inside a container, reading it from standard input.
     * <p>
     * Over standard input rather than as an argument: one of these files carries the task's token,
     * and a command line is readable by every process on the host.
     *
     * @param container Container name.
     * @param path Absolute path inside the container.
     * @return Arguments.
     */
    public List<String> writeFileArguments(String container, String path) {
        // 'tee', so the path is an ARGUMENT rather than text in a script. This was one 'sh -c'
        // with the path concatenated into single quotes three times over, and a path containing a
        // quote ended the quoting and turned the rest into commands - executed in the container as
        // the agent user. The path comes from the agent's own answer, which makes that a command
        // injection across an API boundary rather than a formatting slip.
        //
        // Nothing here needs a shell: the content arrives on standard input and tee writes it,
        // which is the whole of what the removed script did apart from mkdir and chmod - and those
        // are their own calls now, for the same reason.
        return List.of(executable, "exec", "--interactive", target(container), "tee", path);
    }

    /**
     * Returns the arguments that run a command as root in a task's container: what Sokar does with the files it
     * hands to a task, which the agent may read but not change.
     * <p>
     * The command and its arguments stay separate, never one string for a shell: a file name a person chose must
     * only ever be a path.
     *
     * @param container Container name.
     * @param command The command and its arguments.
     * @return Arguments.
     */
    public List<String> asRootArguments(String container, List<String> command) {
        final List<String> arguments = new java.util.ArrayList<>(
                List.of(executable, "exec", "--user", "root", target(container)));
        arguments.addAll(command);
        return arguments;
    }

    /**
     * Returns the arguments that start a command as root in a task's container and return at once, the command going
     * on in the container: a writer that lives as long as the container does.
     *
     * @param container Container name.
     * @param command The command and its arguments.
     * @return Arguments.
     */
    public List<String> asRootDetachedArguments(String container, List<String> command) {
        return asUserDetachedArguments(container, "root", command);
    }

    /**
     * Returns the arguments that start a command as a given user in a task's container and return at once.
     *
     * @param container Container name.
     * @param user The user in the container.
     * @param command The command and its arguments.
     * @return Arguments.
     */
    public List<String> asUserDetachedArguments(String container, String user, List<String> command) {
        final List<String> arguments = new java.util.ArrayList<>(
                List.of(executable, "exec", "--detach", "--user", user, target(container)));
        arguments.addAll(command);
        return arguments;
    }

    /**
     * Returns the arguments that run a command as root in a task's container, reading standard input.
     *
     * @param container Container name.
     * @param command The command and its arguments.
     * @return Arguments.
     */
    public List<String> asRootWithInputArguments(String container, List<String> command) {
        final List<String> arguments = new java.util.ArrayList<>(
                List.of(executable, "exec", "--interactive", "--user", "root", target(container)));
        arguments.addAll(command);
        return arguments;
    }

    /**
     * Returns the arguments that create the directory a file is about to be written into.
     *
     * @param container Container name.
     * @param path Absolute path of the file itself.
     * @return Arguments.
     */
    public List<String> makeParentArguments(String container, String path) {
        return List.of(executable, "exec", target(container), "mkdir", "-p",
                path.substring(0, path.lastIndexOf('/')));
    }

    /**
     * Returns the arguments that set a written file's permissions.
     *
     * @param container Container name.
     * @param path Absolute path inside the container.
     * @param mode Octal permissions.
     * @return Arguments.
     */
    public List<String> setModeArguments(String container, String path, String mode) {
        return List.of(executable, "exec", target(container), "chmod", mode, path);
    }

    /**
     * Returns the argument list that attaches an interactive shell to a running container.
     * <p>
     * Returned rather than executed: attaching replaces the Sokar process with the shell, so the
     * caller does that with {@code execvp} and never comes back.
     *
     * @param container Container name or id.
     * @param shell Shell to run.
     * @return Full argument list, starting with the podman executable.
     */
    /**
     * Returns the arguments that attach a terminal to a program in a running container.
     * <p>
     * The program and its arguments stay separate: joining them into one string would have podman
     * look for an executable whose name contains spaces.
     *
     * @param container Container name.
     * @param command Program and arguments to run inside it.
     * @return Arguments.
     */
    public List<String> attachArguments(String container, List<String> command) {
        return attachArguments(container, command, List.of());
    }

    /**
     * Returns the arguments that attach to a container, with extra arguments for {@code exec}.
     * <p>
     * Pure: nothing here runs anything. {@code environment} is what {@link #terminalFor(String)}
     * returned, resolved by the caller because resolving it costs a round trip into the container.
     *
     * @param container Container name.
     * @param command Program and arguments to run inside it.
     * @param environment Extra arguments for {@code exec}, before the container name.
     * @return Arguments.
     */
    public List<String> attachArguments(String container, List<String> command,
            List<String> environment) {
        final List<String> arguments = new ArrayList<>(
                List.of(executable, "exec", "--interactive", "--tty"));
        arguments.addAll(environment);
        arguments.add(target(container));
        arguments.addAll(command);
        return List.copyOf(arguments);
    }

    /**
     * Returns an argument list for the runtime, for a caller that runs it on this terminal.
     * <p>
     * Exists because an interactive login has to reach the person's own terminal: a command whose
     * output this collected would show them nothing, and a login that prints a URL nobody sees is
     * a login that never finishes.
     *
     * @param arguments What to pass to the runtime.
     * @return The executable followed by those arguments.
     */
    public List<String> arguments(List<String> arguments) {
        final List<String> all = new ArrayList<>();
        all.add(executable);
        all.addAll(arguments);
        return List.copyOf(all);
    }

    /**
     * Copies a path out of a container onto this machine.
     * <p>
     * Used to collect what a login left behind, so the credential is read by the same extractor
     * that reads one from a directory on the node - rather than by a second implementation that
     * would eventually disagree with the first about where an agent keeps things.
     *
     * @param container Container to copy from.
     * @param source Path inside it.
     * @param target Directory on this machine.
     */
    public void copyOut(String container, String source, Path target) {
        runner.runOrFail(podman("cp", target(container) + ":" + source, target.toString()));
    }

    public List<String> attachArguments(String container, String shell) {
        return attachArguments(container, List.of(shell));
    }

    /**
     * Returns the arguments that run a command first and leave a shell behind afterwards.
     * <p>
     * The shell outlives the command on purpose: when the command ends - finished, failed, or
     * refused a credential - the workspace is still there to look at and its work can still be
     * pushed by hand. Attaching to the command alone would take the container down with it.
     *
     * @param container Container name.
     * @param shell Shell to leave behind.
     * @param command Command to run first.
     * @return Arguments.
     */
    public List<String> attachArguments(String container, String shell, String command) {
        return attachArguments(container, shell, command, null);
    }

    /**
     * Returns the arguments that run a command, then leave a shell whose prompt names the task.
     *
     * @param container Container name.
     * @param shell Shell to leave behind.
     * @param command Command to run first, or {@code null} to go straight to the shell.
     * @param label Text for the prompt, or {@code null} to leave the shell's own.
     * @return Arguments.
     */
    public List<String> attachArguments(String container, String shell,
            @Nullable String command, @Nullable String label) {
        return attachArguments(container, shell, command, label, List.of());
    }

    /**
     * Returns the arguments that run a command, leave a shell, and say what terminal it is on.
     *
     * @param container Container name.
     * @param shell Shell to leave behind.
     * @param command Command to run first, or {@code null} to go straight to the shell.
     * @param label Text for the prompt, or {@code null} to leave the shell's own.
     * @param environment Extra arguments for {@code exec}, from {@link #terminalFor(String)}.
     * @return Arguments.
     */
    /**
     * Returns the arguments that join a task's session, creating it if it is not there.
     * <p>
     * <strong>One session per task, whichever command reaches it.</strong> {@code new-session -A}
     * attaches when the session exists and creates it when it does not, so starting a task and
     * attaching to one are the same operation against the same name - which is what makes leaving
     * a window and coming back find what was left.
     * <p>
     * The command goes in as a single argument on purpose: tmux joins several into one string
     * with spaces, which would take a script apart at its quoting.
     *
     * @param container Container name.
     * @param script What the session's first window runs, or {@code null} to leave tmux's default.
     * @param environment Extra arguments for {@code exec}, from {@link #terminalFor(String)}.
     * @return Arguments.
     */
    public List<String> sessionArguments(String container, @Nullable String script,
            List<String> environment) {
        final List<String> session = new ArrayList<>(List.of("tmux", "-f", Containerfile.TMUX_CONF,
                "new-session", "-A", "-s", Containerfile.SESSION));
        if (script != null) {
            session.add(script);
        }
        return attachArguments(container, List.copyOf(session), environment);
    }

    /**
     * What a task's terminal shows, read without attaching to it.
     *
     * @param lines Its last lines, the blank screen below them left out.
     * @param live Whether the task has a terminal session to read: {@code false} for work without one, and then
     *        {@code lines} is empty.
     */
    public record Screen(List<String> lines, boolean live) {
    }

    /** As many lines as a session keeps, and so the most a screen can show. */
    public static final int SCREEN_LINES = Integer.parseInt(Containerfile.SCROLLBACK);

    /**
     * Reads the last lines of a task's terminal session, as tmux keeps them, without attaching to it.
     * <p>
     * For a console showing what runs there: nobody attached sees anything happen, and the screen is read
     * as it is, a snapshot a client asks for again rather than a stream.
     *
     * @param container The task.
     * @param last How many lines, at most {@link #SCREEN_LINES}.
     * @param escapes Whether to keep the colour and attribute escapes, for a client that draws them.
     * @return What it shows.
     */
    public Screen screen(String container, int last, boolean escapes) {
        final int lines = Math.max(1, Math.min(last, SCREEN_LINES));
        final CommandResult result;
        try {
            final List<String> capture = new ArrayList<>(List.of("exec", target(container), "tmux", "capture-pane",
                    "-p", "-J"));
            if (escapes) {
                capture.add("-e");
            }
            capture.addAll(List.of("-S", "-" + lines, "-t", Containerfile.SESSION));
            result = runner.run(podman(capture.toArray(String[]::new)));
        } catch (RuntimeException ex) {
            return new Screen(List.of(), false);
        }
        if (result.exitCode() != 0) {
            return new Screen(List.of(), false);
        }
        final List<String> shown = new ArrayList<>(List.of(result.standardOutput().split("\n", -1)));
        while (!shown.isEmpty() && shown.get(shown.size() - 1).replaceAll("\u001b\\[[0-9;]*m", "").isBlank()) {
            shown.remove(shown.size() - 1);
        }
        return new Screen(List.copyOf(shown.subList(Math.max(0, shown.size() - lines), shown.size())), true);
    }

    /**
     * Types one line into a task's terminal session, then Enter, as a person at its keyboard would.
     * <p>
     * Only ever a line of Sokar's own, never anything somebody wrote: what arrives for an agent reaches it as a file
     * the host checked, and a line typed here is read as the person's.
     *
     * @param container The task.
     * @param line What to type.
     * @return Whether both keystrokes reached the session.
     */
    public boolean type(String container, String line) {
        try {
            final String id = target(container);
            return runner.run(podman("exec", id, "tmux", "send-keys", "-t", Containerfile.SESSION, "-l", line))
                    .exitCode() == 0
                    && runner.run(podman("exec", id, "tmux", "send-keys", "-t", Containerfile.SESSION, "Enter"))
                            .exitCode() == 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Says whether the session's own shell reported that it ended.
     * <p>
     * This is how leaving a window is told from finishing the work. Nothing observable from
     * outside distinguishes them - both end the command that was attached - so the shell says so
     * itself, into {@link Containerfile#SESSION_ENDED} inside the container, and the host reads it.
     * Asking tmux whether the session survived would answer the same question and fail in the
     * wrong direction; {@link Containerfile#SESSION_ENDED} says which and why.
     * <p>
     * <strong>Absence means "keep", not "remove".</strong> A killed session, a crashed container
     * or a container that cannot be asked all leave no marker, and all of them keep the task.
     *
     * @param container Container name.
     * @return Whether this session's shell returned.
     */
    public boolean sessionEnded(String container) {
        try {
            return runner.run(podman("exec", target(container), "test", "-f", Containerfile.SESSION_ENDED))
                    .exitCode() == 0;
        } catch (RuntimeException ex) {
            // A container that cannot be asked has not told us it finished, and "not finished"
            // keeps the task. Never discard work because a question could not be put.
            return false;
        }
    }

    public List<String> attachArguments(String container, String shell,
            @Nullable String command, @Nullable String label, List<String> environment) {
        return attachArguments(container, List.of(shell, "-lc", attachScript(shell, command, label)),
                environment);
    }

    /**
     * Builds what a task's window runs: the command, the terminal put back, then a shell.
     * <p>
     * Separate from the argument list because the same script is run two ways - directly by a
     * shell, and as a session's first window - and a second copy of it would drift.
     *
     * @param shell Shell to leave behind.
     * @param command Command to run first, or {@code null} to go straight to the shell.
     * @param label Text for the prompt, or {@code null} to leave the shell's own.
     * @return The script.
     */
    public String attachScript(String shell, @Nullable String command, @Nullable String label) {

        final StringBuilder script = new StringBuilder();
        // Cleared first: a marker left by an earlier session would say this one had already ended
        // before it began.
        script.append("rm -f ").append(Containerfile.SESSION_ENDED).append("; ");
        if (command != null) {
            // The agent starts at the top of the terminal, not wherever the launch report left
            // the cursor. Starting a task prints two dozen lines - the token, the gate, every
            // reachable host - and the agent then drew its own full-screen interface into the
            // middle of them, which reads as two programs sharing one screen.
            //
            // ESC[H puts the cursor home and ESC[2J erases the screen. ESC[3J - which erases the
            // SCROLLBACK, and which is what 'clear' sends on a modern terminfo - is deliberately
            // NOT here: the report carries the gate URL and what the container may reach, and
            // scrolling back to it is the only way to read it once the agent owns the screen.
            script.append("printf '\\033[H\\033[2J'; ");
            script.append(command).append("; ");
            // The agent draws a full-screen interface, and what it leaves behind is inherited by
            // the shell that follows. Four things have to be undone, and only the first two were:
            //
            //   [?1049l  leave the alternate screen
            //   [?25h    show the cursor again
            //   [r       reset the scrolling region - a full-screen app sets one, and a terminal
            //            that keeps it confines every later line to that band. This is what made
            //            a session look hung: the shell WAS there, in a strip, with the cursor
            //            somewhere else entirely.
            //   [?7h     autowrap back on, so long lines wrap instead of overwriting themselves
            //
            // Mouse reporting goes too: an agent that enabled it and did not turn it off leaves a
            // shell where clicking types escape sequences.
            //
            // Then the screen is cleared. Resetting the modes is not enough on its own: leaving
            // the alternate screen restores the cursor position saved when it was ENTERED, and an
            // agent that never entered it - as the stub does not - restores a stale one. The
            // result reads as a hung session: the last output at the bottom, the cursor at the
            // top, and a shell that is running and does not look like it.
            //
            // Erased rather than the cursor moved, because what is on the screen belongs to the agent
            // that just ended - and erased as at the start, the screen only: 'clear' on a current
            // terminfo erases the scrollback too, where the conversation is kept for anyone who wants
            // to read back (the operator, 2026-10-01).
            script.append("printf '\\033[?1049l\\033[?25h\\033[0m\\033[r\\033[?7h"
                    + "\\033[?1000l\\033[?1002l\\033[?1003l\\033[?1006l'; stty sane; ");
            script.append("printf '\\033[H\\033[2J'; ");
        }
        if (label != null) {
            // A container hostname says nothing about which task it is, and an operator with
            // several open shells has no other way to tell them apart.
            // Quoted as a word, not put between quotes as it came: a quote in it would end the string.
            script.append("export SOKAR_PROMPT=").append(ShellWords.quote(List.of(label))).append("; ");
            script.append("export PROMPT_COMMAND=\"PS1='sokar[\\$SOKAR_PROMPT] \\w\\$ '\"; ");
        }
        // NOT 'exec', and that is the whole mechanism. 'exec' replaces this process with the
        // shell, so nothing can run after the shell returns - and something has to, because the
        // shell returning is the only unambiguous evidence that the person finished rather than
        // detached. As a child it returns here, and the next statement says so.
        script.append(shell).append(" -l; : > ").append(Containerfile.SESSION_ENDED);
        return script.toString();
    }
}
