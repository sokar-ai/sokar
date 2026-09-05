package org.fuin.sokar.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a task container is created with.
 * <p>
 * Built rather than passed as a raw argument list, so that the security-relevant flags are stated
 * once, in one place, and are visible to a test.
 */
public class ContainerSpec {

    /**
     * Address a container reaches the host at when podman uses pasta, which podman 5 does by
     * default. Kept only as the fallback when podman cannot be asked - podman 4 answers with the
     * host's own LAN address instead, so this is a guess and is treated as one.
     */
    public static final String HOST_LOOPBACK = "169.254.1.2";

    /** Name podman writes into a container's {@code /etc/hosts} for the host it runs on. */
    public static final String HOST_FROM_CONTAINER = "host.containers.internal";

    private final String name;

    private final String image;

    private final List<String> command = new ArrayList<>();

    private final Map<String, String> annotations = new LinkedHashMap<>();

    private final Map<Path, String> volumes = new LinkedHashMap<>();

    private final List<String> resolvers = new ArrayList<>();

    private final Map<String, String> environment = new LinkedHashMap<>();

    @org.jspecify.annotations.Nullable
    private String memory = org.fuin.sokar.core.project.Limits.DEFAULT_MEMORY;

    @org.jspecify.annotations.Nullable
    private String cpus;

    private int pids = org.fuin.sokar.core.project.Limits.DEFAULT_PIDS;

    /**
     * Constructor with the required data.
     *
     * @param name Container name.
     * @param image Image to run.
     */
    public ContainerSpec(String name, String image) {
        this.name = name;
        this.image = image;
    }

    /**
     * Sets the command the container runs.
     *
     * @param arguments Command and arguments.
     * @return This instance.
     */
    public ContainerSpec command(String... arguments) {
        command.clear();
        command.addAll(List.of(arguments));
        return this;
    }

    /**
     * Adds an OCI annotation. The hooks are gated on one of these, so an unannotated container is
     * invisible to them.
     *
     * @param key Annotation key.
     * @param value Annotation value.
     * @return This instance.
     */
    public ContainerSpec annotation(String key, String value) {
        annotations.put(key, value);
        return this;
    }

    /**
     * Mounts a host directory into the container.
     *
     * @param host Directory on the host.
     * @param mountPoint Path inside the container.
     * @return This instance.
     */
    public ContainerSpec volume(Path host, String mountPoint) {
        volumes.put(host, mountPoint);
        return this;
    }

    /**
     * Sets an environment variable inside the container.
     * <p>
     * <strong>Only ever a phantom token, never a real credential.</strong> A variable passed here
     * reaches podman's command line, so it is visible in the host's process list for as long as
     * the create call runs, and it is readable from the container image metadata afterwards. A
     * phantom token is worth nothing outside this task; the real credential must never take this
     * path.
     *
     * @param name Variable name.
     * @param value Variable value.
     * @return This instance.
     */
    public ContainerSpec environment(String name, String value) {
        environment.put(name, value);
        return this;
    }

    /**
     * Points the container's resolver at an address.
     *
     * @param address Resolver address, usually loopback inside the container.
     * @return This instance.
     */
    public ContainerSpec resolver(String address) {
        resolvers.add(address);
        return this;
    }

    /**
     * Returns the container name.
     *
     * @return Name.
     */
    public String name() {
        return name;
    }

    /**
     * Sets what the container may consume.
     *
     * @param limits The limits.
     * @return This instance.
     */
    public ContainerSpec limits(org.fuin.sokar.core.project.Limits limits) {
        this.memory = limits.memory();
        this.cpus = limits.cpus();
        this.pids = limits.pids();
        return this;
    }

    /**
     * Renders the podman arguments, without the {@code create} verb.
     *
     * @return Arguments.
     */
    public List<String> toArguments() {

        final List<String> arguments = new ArrayList<>();

        arguments.add("--name");
        arguments.add(name);

        // Nothing in a task container ever needs to gain privileges. Set here rather than left to
        // the image, because an image is user-supplied and this is not negotiable.
        arguments.add("--security-opt");
        arguments.add("no-new-privileges");

        arguments.add("--cap-drop");
        arguments.add("ALL");

        // PID 1 is 'sleep infinity' and never reaps, so without this every process the agent
        // orphans stays a zombie holding a PID until the container dies.
        arguments.add("--init");

        if (memory != null) {
            arguments.add("--memory");
            arguments.add(memory);
        }
        if (cpus != null) {
            arguments.add("--cpus");
            arguments.add(cpus);
        }
        arguments.add("--pids-limit");
        arguments.add(String.valueOf(pids));

        // A private network namespace is what makes a per-container firewall possible at all: the
        // nft hook installs the ruleset inside this namespace. Do NOT spell this as
        // 'pasta:<options>': that replaces podman's own pasta defaults instead of adding to them,
        // and the measured result was open egress with the ruleset still loaded.
        arguments.add("--network");
        arguments.add("private");

        environment.forEach((name, value) -> {
            arguments.add("--env");
            arguments.add(name + "=" + value);
        });

        resolvers.forEach(address -> {
            // Without this the agent uses the host's resolver, the firewall sees only addresses,
            // and a name the operator would have recognised reaches the prompt as a bare IP.
            arguments.add("--dns");
            arguments.add(address);
        });

        annotations.forEach((key, value) -> {
            arguments.add("--annotation");
            arguments.add(key + "=" + value);
        });

        volumes.forEach((host, mountPoint) -> {
            arguments.add("--volume");
            // 'Z' relabels for SELinux. Without it the mount is unreadable on an enforcing host,
            // which is the platform the hooks were verified on.
            arguments.add(host + ":" + mountPoint + ":Z");
        });

        arguments.add(image);
        arguments.addAll(command);

        return List.copyOf(arguments);
    }
}
