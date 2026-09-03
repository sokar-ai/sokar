package org.fuin.sokar.shield;

import java.util.List;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandRunner;

/**
 * Changes a running container's egress policy without restarting it.
 * <p>
 * An allow decision has to take effect on the agent's next attempt, so the address is added to the
 * live nftables set rather than by regenerating and reloading the ruleset. Reloading would drop
 * conntrack state and kill every connection the agent already had open.
 * <p>
 * Only ever adds to the allow sets. There is no method here that opens the chain policy, and there
 * should not be: everything this class can do widens access by exactly one address.
 */
public class EgressPolicy {

    private final CommandRunner runner;

    private final long containerPid;

    /**
     * Constructor.
     *
     * @param runner Runs external programs.
     * @param containerPid Host process id of the container's init process.
     */
    public EgressPolicy(CommandRunner runner, long containerPid) {
        this.runner = runner;
        this.containerPid = containerPid;
    }

    /**
     * Adds one address to the allow set.
     *
     * @param address IPv4 or IPv6 address.
     */
    public void allow(String address) {
        final String set = address.contains(":") ? "allowed_v6" : "allowed_v4";
        runner.runOrFail(Command.of(nsenter("nft", "add", "element", "inet", "sokar", set,
                "{ " + address + " }")));
    }

    /**
     * Returns the addresses currently allowed.
     *
     * @param set Set name, {@code allowed_v4} or {@code allowed_v6}.
     * @return Raw output of {@code nft list set}.
     */
    public String list(String set) {
        return runner.runOrFail(Command.of(nsenter("nft", "list", "set", "inet", "sokar", set)))
                .standardOutput();
    }

    private List<String> nsenter(String... command) {
        // setns() is per-thread and the JVM gives no control over which thread runs what, so a
        // child process is the only reliable way into the container's namespace.
        final List<String> all = new java.util.ArrayList<>(
                List.of("nsenter", "--target", String.valueOf(containerPid), "--net"));
        all.addAll(List.of(command));
        return List.copyOf(all);
    }
}
