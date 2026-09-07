package org.fuin.sokar.shield;

import java.util.List;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
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

    /** What nft says about an element that is already in the set. */
    private static final String ALREADY_THERE = "File exists";

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
     * <p>
     * An address that is already in the set is the outcome asked for, not a failure. {@code nft}
     * disagrees - it answers {@code File exists} - and a watcher restarted against a container
     * that kept running re-applies every decision it recorded, which would otherwise report each
     * one as an allow that did not take effect.
     *
     * @param address IPv4 or IPv6 address.
     */
    public void allow(String address) {
        final String set = address.contains(":") ? "allowed_v6" : "allowed_v4";
        final CommandResult result = runner.run(Command.of(
                nsenter("nft", "add", "element", "inet", "sokar", set, "{ " + address + " }")));
        if (!result.successful() && !result.standardError().contains(ALREADY_THERE)) {
            result.orFail();
        }
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

    /**
     * Returns the command that runs the given program inside the container's network namespace.
     * <p>
     * {@code podman unshare} first, because a rootless container's namespaces belong to the
     * operator's user namespace and {@code nsenter} cannot join them from outside it.
     * {@code setns} is per-thread and the JVM gives no control over which thread runs what, so a
     * child process is the only reliable way in either case.
     *
     * @param command Program and arguments to run inside the namespace.
     * @param containerPid Host process id of the container's init process.
     * @return Full argument list.
     */
    public static List<String> inNamespace(long containerPid, List<String> command) {
        final List<String> all = new java.util.ArrayList<>(List.of(
                "podman", "unshare", "nsenter", "--target", String.valueOf(containerPid), "--net"));
        all.addAll(command);
        return List.copyOf(all);
    }

    private List<String> nsenter(String... command) {
        return inNamespace(containerPid, List.of(command));
    }
}
