package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.runtime.ContainerName;
import org.fuin.sokar.runtime.ContainerSpec;
import org.fuin.sokar.runtime.Podman;
import org.fuin.sokar.shield.NftRuleset;
import org.fuin.sokar.wire.Sidecar;

/**
 * Runs one task: generate the policy, build the image, create the container, start it.
 * <p>
 * The order matters and is not an implementation detail. The ruleset and the sidecar are written
 * <em>before</em> the container is created, because the nft hook reads them while the container is
 * being created. Writing them afterwards would leave a window in which a container exists without
 * its firewall, and the hook would fail closed rather than wait.
 */
public class TaskRunner {

    private final Podman podman;

    private final SokarPaths paths;

    /**
     * Constructor.
     *
     * @param runner Runs external programs.
     * @param paths Where files go.
     */
    public TaskRunner(CommandRunner runner, SokarPaths paths) {
        this.podman = new Podman(runner);
        this.paths = paths;
    }

    /**
     * Returns the container name for one task run.
     *
     * @param project The project.
     * @param task Task name.
     * @param runId Identifier unique within the task.
     * @return Container name.
     */
    public String containerName(Project project, String task, String runId) {
        return ContainerName.of(project, task, runId);
    }

    /**
     * Prepares and starts a container for one task.
     * <p>
     * The name is passed in rather than returned, so that a caller can clean up a container that
     * was created but failed to start. Returning it would leave that container behind, because the
     * failure happens before the return.
     *
     * @param project The project.
     * @param container Container name.
     * @param out Where progress is reported.
     * @throws IOException If a file cannot be written.
     */
    public void start(Project project, String container, PrintWriter out) throws IOException {

        final Path state = paths.containerState(container);
        Files.createDirectories(state);

        final Path ruleset = state.resolve("ruleset.nft");
        Files.writeString(ruleset, rulesetFor(project), StandardCharsets.UTF_8);
        out.println("policy    " + ruleset);

        final Path sidecarFile = state.resolve("sidecar.json");
        new Sidecar(Sidecar.VERSION, project.name(),
                project.securityClass().name().toLowerCase(),
                ruleset.toString(), state.toString()).writeTo(sidecarFile);
        out.println("sidecar   " + sidecarFile);

        final String image = podman.buildImage(project, paths.buildContext(project.name()));
        out.println("image     " + image);

        podman.create(new ContainerSpec(container, image)
                .command("sleep", "infinity")
                .resolver(org.fuin.sokar.shield.DnsPolicy.LISTEN_ADDRESS)
                .annotation(Sidecar.ANNOTATION, sidecarFile.toString()));
        out.println("container " + container);

        // If the nft hook fails, this is where it stops: the container never reaches running.
        podman.start(container);
        out.println("started   yes");
    }

    private String rulesetFor(Project project) {
        final NftRuleset ruleset = new NftRuleset(project.securityClass());
        if (project.securityClass() != SecurityClass.OFFLINE) {
            // The curated egress sets arrive with the shield phase. Until then a non-offline
            // project is no more open than an offline one, which is the safe direction to be
            // wrong in.
            ruleset.allowV4("127.0.0.0/8");
        }
        return ruleset.render();
    }

    /**
     * Returns the command that attaches a shell to a running container.
     *
     * @param container Container name.
     * @param shell Shell to run.
     * @return Argument list.
     */
    public List<String> attachCommand(String container, String shell) {
        return podman.attachArguments(container, shell);
    }

    /**
     * Stops and removes a container.
     *
     * @param container Container name.
     */
    public void remove(String container) {
        podman.remove(container);
    }
}
