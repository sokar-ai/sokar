package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The git endpoint a task pushes to: where it binds, where the firewall must let the container
 * reach it, and whether the two agree.
 * <p>
 * Split out of {@code TaskRunCommand} after {@link CredentialWiring}. The three questions here are
 * one decision seen from three sides, and getting them out of step is a push that hangs until it
 * times out - which is what happened when the address was a constant and podman answered with
 * something else.
 */
final class GateWiring {

    private final SokarContext context;

    private final CredentialWiring.Recorder recorder;

    private final Path projectFile;

    private final @Nullable String upstream;

    /** Which repository of the project this gate serves, or {@code null} for the project's own. */
    private final @Nullable String repository;

    /**
     * Constructor with what the run decided.
     *
     * @param context Where podman and the paths come from.
     * @param recorder Where the gate process is recorded, so a resumed task starts it again.
     * @param projectFile The project file this task runs from.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     */
    GateWiring(SokarContext context, CredentialWiring.Recorder recorder, Path projectFile,
            @Nullable String upstream) {
        this(context, recorder, projectFile, upstream, null);
    }

    /**
     * Constructor naming the repository this gate is for.
     *
     * @param context Where podman and the paths come from.
     * @param recorder Where the gate process is recorded, so a resumed task starts it again.
     * @param projectFile The project file this task runs from.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     * @param repository Which repository of the project, or {@code null} for its own.
     */
    GateWiring(SokarContext context, CredentialWiring.Recorder recorder, Path projectFile,
            @Nullable String upstream, @Nullable String repository) {
        this.context = context;
        this.recorder = recorder;
        this.projectFile = projectFile;
        this.upstream = upstream;
        this.repository = repository;
    }

    void startGate(TaskRunner runner, TaskWorkspace workspace,
            @org.jspecify.annotations.Nullable String gateAddress, String container,
            org.fuin.sokar.core.project.Project project, PrintWriter out,
            PrintWriter err) {

        final java.nio.file.Path state = context.paths().containerState(container);
        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                SokarBinary.path(),
                "gate", "serve",
                // The NAME, because that is what 'gate serve' takes now. It resolves on its own
                // side, and by the time this runs the launch has recorded where the file is - so
                // the gate and the task cannot end up reading two different files.
                "--project", project.name(),
                "--address", gateBind(gateAddress, err),
                "--port", String.valueOf(workspace.port()),
                "--pid-file", state.resolve("gate.pid").toString()));
        if (repository != null) {
            // On the recorded command line, so that resuming the task brings back a gate on the
            // same mirror. A resumed task that served the project's own repository instead would
            // offer the agent a different history under the same URL.
            command.add("--repository");
            command.add(repository);
        }

        try {
            final ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve("gate.log").toFile());
            builder.environment().put("SOKAR_GATE_TOKEN", workspace.token().value());
            if (upstream != null) {
                builder.environment().put("SOKAR_GATE_UPSTREAM", upstream);
            }
            builder.start();
            recorder.record("gate", command, java.util.Map.copyOf(builder.environment().entrySet()
                    .stream()
                    .filter(entry -> entry.getKey().startsWith("SOKAR_GATE_"))
                    .collect(java.util.stream.Collectors.toMap(
                            java.util.Map.Entry::getKey, java.util.Map.Entry::getValue))),
                    TaskHelpers.AFTER);
            out.println("gate      " + workspace.url(project));
            final String mismatch = gateAddress == null ? null
                    : gateReachability(runner, container, gateAddress);
            if (mismatch != null) {
                err.println("sokar: " + mismatch);
                err.flush();
            }
            out.flush();
        } catch (java.io.IOException ex) {
            err.println("sokar: could not start the git gate: " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Returns the address the git gate binds.
     * <p>
     * Loopback wherever that works, so the endpoint an agent pushes to is not on the operator's
     * network at all. It works because {@link org.fuin.sokar.runtime.LoopbackMapping} tells pasta
     * to send the container's address for this host to the host's loopback - which podman only
     * does under pasta, and ignores in silence under slirp4netns.
     * <p>
     * The fallback binds every interface rather than refusing to run: an unreachable gate breaks
     * the task, while a reachable one still needs the per-task token every request carries. It is
     * said out loud, because that is a difference an operator should know about their machine.
     *
     * @param gateAddress Address the container reaches this host at, as podman answered it.
     * @param err Where the fallback is reported.
     * @return Address to bind.
     */
    String gateBind(@org.jspecify.annotations.Nullable String gateAddress,
            PrintWriter err) {

        final String mapped = org.fuin.sokar.runtime.ContainerSpec.HOST_LOOPBACK;
        final java.util.Optional<String> rootless = context.podman().rootlessNetworkCmd();
        final boolean pasta =
                rootless.filter(org.fuin.sokar.runtime.LoopbackMapping.PASTA::equals).isPresent();
        if (pasta && mapped.equals(gateAddress)) {
            return "127.0.0.1";
        }
        final String reason = pasta
                ? "a container reaches this host at " + gateAddress + ", not " + mapped
                        + ", which is the address pasta was told to map"
                : "podman connects a rootless container with "
                        + rootless.orElse("something it will not name")
                        + ", and only pasta can map the host's loopback";
        err.println("sokar: the git gate is bound to every interface and is reachable from this"
                + " machine's network: " + reason + "; the per-task token is what keeps it shut");
        err.flush();
        return "0.0.0.0";
    }

    /**
     * Returns the address the firewall must open for the git gate.
     * <p>
     * Asked of podman rather than assumed. The name podman uses does not resolve on the host, so
     * this was a constant - and the constant is wrong on podman 4, which answers with the host's
     * own LAN address instead of pasta's {@code 169.254.1.2}. Ubuntu 24.04 LTS ships podman 4.9.3,
     * so every gate there was firewalled off and every push hung until it timed out.
     * <p>
     * Costs one throwaway container against an image the task already needs. When podman cannot
     * answer, the old constant is used and said so: a wrong guess breaks the gate, which is
     * recoverable, while refusing to run breaks the task, which is not.
     *
     * @param project The project, for the image to ask with.
     * @return Address a container reaches this host at.
     */
    String gateAddress(org.fuin.sokar.core.project.Project project, PrintWriter err) {
        final java.util.Optional<String> asked =
                context.podman().hostAddressFromContainer(project.baseImage());
        if (asked.isPresent()) {
            return asked.get();
        }
        err.println("sokar: could not ask podman where a container reaches"
                + " this host; assuming " + TaskWorkspace.gateAddress()
                + ", and the git gate will not work if that is wrong");
        return TaskWorkspace.gateAddress();
    }

    /**
     * Asks the container where it reaches this host, and compares that with the address the
     * firewall was told to allow.
     * <p>
     * The two are derived independently - one by podman, one by
     * {@link TaskWorkspace#gateAddress()} - and when they disagreed the only symptom was a push
     * that hung for two minutes and then failed to connect. Cheap to check, so it is checked.
     *
     * @param container Container name.
     * @return {@code null} if the gate is reachable, otherwise a message saying why not.
     */
    String gateReachability(TaskRunner runner, String container, String gateAddress) {
        try {
            final java.nio.file.Path out = java.nio.file.Files.createTempFile("sokar-hosts", "");
            try {
                final int code = runner.execute(container, java.util.Map.of(),
                        java.util.List.of("cat", "/etc/hosts"), out,
                        java.time.Duration.ofSeconds(20));
                if (code != 0) {
                    return null;
                }
                return TaskWorkspace.verify(java.nio.file.Files.readString(out), gateAddress);
            } finally {
                java.nio.file.Files.deleteIfExists(out);
            }
        } catch (java.io.IOException ex) {
            return null;
        }
    }
}
