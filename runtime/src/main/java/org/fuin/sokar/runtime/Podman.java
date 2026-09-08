package org.fuin.sokar.runtime;

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
        this.runner = runner;
        this.executable = executable;
        this.networkConfiguration = networkConfiguration;
    }

    private Command podman(String... arguments) {
        final List<String> all = new ArrayList<>();
        all.add(executable);
        all.addAll(List.of(arguments));
        return Command.of(all);
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
            return Optional.of("podman does not answer 'podman version'; Sokar needs podman "
                    + MINIMUM_MAJOR + " or newer");
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
     * {@code task run}, so that a task resumed later comes up with the same networking as one
     * that never stopped. A gate the container cannot reach only shows up as a push that hangs.
     *
     * @param container Container name or id.
     */
    public void start(String container) {
        runner.runOrFail(withNetworkConfiguration(podman("start", container)));
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
        final CommandResult result = runner.run(podman("diff", container));
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
        final CommandResult result = runner.run(podman("container", "inspect",
                "--format", "{{.Id}}", container));
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
                "--format", "{{.State.Pid}}", container));
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
        runner.run(podman("stop", "--time", "5", container));
    }

    /**
     * Stops a container, then removes it. Neither step fails the caller: this runs on the way out,
     * where a container that is already gone is the desired state, not a problem.
     *
     * @param container Container name or id.
     */
    public void remove(String container) {
        runner.run(podman("stop", "--time", "5", container));
        runner.run(podman("rm", "--force", container));
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
        arguments.add(container);
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
                "--format", "{{.ImageName}}\t{{.Image}}", container));
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
        return runner.runOrFail(podman("ps", "--all", "--format",
                        "{{.Names}}\t{{.Status}}\t{{.StartedAt}}\t{{.ExitedAt}}"))
                .standardOutput().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split("\t", 4))
                .filter(parts -> ContainerName.isTask(parts[0]))
                .map(parts -> new ContainerSummary(parts[0], parts.length > 1 ? parts[1] : "",
                        since(parts)))
                .toList();
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
    public int execute(String container, Map<String, String> environment, List<String> command,
            Path output, Duration timeout) {

        final List<String> arguments = new ArrayList<>(List.of(executable, "exec"));
        arguments.addAll(ContainerSpec.passedThrough(environment.keySet()));
        arguments.add(container);
        arguments.addAll(command);

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
     * @param mode Octal permissions to set.
     * @return Arguments.
     */
    public List<String> writeFileArguments(String container, String path, String mode) {
        final String directory = path.substring(0, path.lastIndexOf('/'));
        return List.of(executable, "exec", "--interactive", container, "sh", "-c",
                "mkdir -p '" + directory + "' && cat > '" + path + "' && chmod " + mode
                        + " '" + path + "'");
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
        final List<String> arguments = new ArrayList<>(
                List.of(executable, "exec", "--interactive", "--tty", container));
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
        runner.runOrFail(podman("cp", container + ":" + source, target.toString()));
    }

    public List<String> attachArguments(String container, String shell) {
        return List.of(executable, "exec", "--interactive", "--tty", container, shell);
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

        final StringBuilder script = new StringBuilder();
        if (command != null) {
            script.append(command).append("; ");
            // The agent draws a full-screen interface. When it ends, the terminal is still in raw
            // mode and possibly on the alternate screen, so the shell that follows inherits a
            // scrambled display. Leave it, show the cursor, restore line discipline.
            script.append("printf '\\033[?1049l\\033[?25h\\033[0m'; stty sane; ");
        }
        if (label != null) {
            // A container hostname says nothing about which task it is, and an operator with
            // several open shells has no other way to tell them apart.
            script.append("export SOKAR_PROMPT='").append(label).append("'; ");
            script.append("export PROMPT_COMMAND=\"PS1='sokar[\\$SOKAR_PROMPT] \\w\\$ '\"; ");
        }
        script.append("exec ").append(shell).append(" -l");
        return List.of(executable, "exec", "--interactive", "--tty", container, shell, "-lc",
                script.toString());
    }
}
