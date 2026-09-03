package org.fuin.sokar.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        this.runner = runner;
        this.executable = executable;
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
     *
     * @param container Container name or id.
     */
    public void start(String container) {
        runner.runOrFail(podman("start", container));
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
    public List<String> attachArguments(String container, String shell) {
        return List.of(executable, "exec", "--interactive", "--tty", container, shell);
    }
}
