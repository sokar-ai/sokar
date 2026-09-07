package org.fuin.sokar.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What makes the host's loopback reachable from a task container, and only from one.
 * <p>
 * pasta gives a rootless container an address for the host - {@link ContainerSpec#HOST_LOOPBACK} -
 * but forwards it to the host's routable address, so a listener on {@code 127.0.0.1} is refused.
 * That is why the git gate used to bind every interface, which put it on the operator's LAN with
 * nothing but a per-task token in front of it.
 * <p>
 * {@code --map-host-loopback} sends that address to the host's loopback instead, and there are
 * three ways to ask for it. Two are wrong:
 * <ul>
 * <li>{@code --network pasta:--map-host-loopback,...} <strong>replaces</strong> podman's own pasta
 * defaults rather than adding to them. Measured: egress was wide open with the nft ruleset still
 * loaded and every check reporting success.</li>
 * <li>A {@code pasta_options} line in the {@code containers.conf} drop-in {@code sokar setup}
 * writes keeps those defaults - measured, podman inserts the option and keeps the rest - but it
 * applies to every container the operator runs, so an unrelated container would reach services on
 * their loopback too.</li>
 * </ul>
 * The third is this one: the same setting in a file named by {@code CONTAINERS_CONF_OVERRIDE},
 * which podman merges last, for the {@code podman start} that Sokar itself runs. Measured on
 * podman 5.8.1: the defaults survive, the mapping takes effect, and a container the operator
 * starts is still refused.
 * <p>
 * Only {@code podman start} reads it. {@code create} stores the container's configuration and
 * never builds the pasta command line, so setting it there does nothing at all - measured, and
 * invisible: the container runs and only the push hangs.
 */
public final class LoopbackMapping {

    /** Variable naming a configuration file podman merges over everything else. */
    public static final String VARIABLE = "CONTAINERS_CONF_OVERRIDE";

    /** What {@code podman info} answers when pasta is the rootless network command. */
    public static final String PASTA = "pasta";

    private LoopbackMapping() {
        throw new UnsupportedOperationException("It is not allowed to create an instance");
    }

    /**
     * Returns the configuration that maps the container's address for this host to its loopback.
     *
     * @return Content of a {@code containers.conf} fragment.
     */
    public static String configuration() {
        return """
                # Written by sokar for the containers it starts itself, not for podman as a whole.
                # It makes the git gate reachable on the host's loopback, so the gate does not
                # have to bind every interface and sit on the operator's LAN.
                [network]
                pasta_options = ["--map-host-loopback", "%s"]
                """.formatted(ContainerSpec.HOST_LOOPBACK);
    }

    /**
     * Writes the configuration, creating the directory it goes in.
     *
     * @param file Where to write it.
     * @return The file.
     * @throws ContainerException If it cannot be written.
     */
    public static Path write(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, configuration(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new ContainerException("Cannot write " + file, ex);
        }
    }
}
