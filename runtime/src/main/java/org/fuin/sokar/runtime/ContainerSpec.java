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

    private final String name;

    private final String image;

    private final List<String> command = new ArrayList<>();

    private final Map<String, String> annotations = new LinkedHashMap<>();

    private final Map<Path, String> volumes = new LinkedHashMap<>();

    private final List<String> resolvers = new ArrayList<>();

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

        // A private network namespace is what makes a per-container firewall possible at all: the
        // nft hook installs the ruleset inside this namespace.
        arguments.add("--network");
        arguments.add("private");

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
