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

    /**
     * Returns the podman version.
     *
     * @return Version string such as {@code 5.7.0}.
     */
    public String version() {
        return runner.runOrFail(podman("version", "--format", "{{.Client.Version}}")).trimmedOutput();
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
        runner.runOrFail(podman("build", "--tag", project.imageName(),
                "--file", containerfile.toString(), contextDirectory.toString()));
        return project.imageName();
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
        return runner.runOrFail(podman(arguments.toArray(String[]::new))).trimmedOutput();
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
        environment.forEach((name, value) -> {
            arguments.add("--env");
            arguments.add(name + "=" + value);
        });
        arguments.add(container);
        arguments.addAll(command);
        return runner.run(podman(arguments.toArray(new String[0])));
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
     * Lists Sokar's containers with the state the runtime reports for each.
     *
     * @return Summaries, running or not, in the runtime's own order.
     */
    public List<ContainerSummary> sokarTasks() {
        // A tab rather than a space: the state is a phrase ("Up 4 minutes"), so anything the
        // state itself can contain cannot be the separator.
        return runner.runOrFail(podman("ps", "--all", "--format", "{{.Names}}\t{{.Status}}"))
                .standardOutput().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> line.split("\t", 2))
                .filter(parts -> ContainerName.isSokar(parts[0]))
                .map(parts -> new ContainerSummary(parts[0], parts.length > 1 ? parts[1] : ""))
                .toList();
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
        environment.forEach((name, value) -> {
            arguments.add("--env");
            arguments.add(name + "=" + value);
        });
        arguments.add(container);
        arguments.addAll(command);

        try {
            final Process process = new ProcessBuilder(arguments)
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
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
