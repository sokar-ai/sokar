package org.fuin.sokar.shield;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.fuin.sokar.core.project.SecurityClass;

/**
 * Generates the dnsmasq configuration for a task container's resolver.
 * <p>
 * A resolver of Sokar's own runs inside the container's network namespace, and the container's
 * {@code resolv.conf} points at it. Without one, the agent talks to whatever resolver the host
 * uses and the firewall sees only addresses - so a name an operator would have recognized arrives
 * as an IP nobody can place, and the Allow prompt becomes a guess.
 * <p>
 * <strong>The default answer is NXDOMAIN.</strong> Names resolve only if the project allows them,
 * which makes DNS the same shape as the packet filter: deny by default, widen deliberately. That
 * also closes DNS exfiltration, where an agent encodes data into names nobody ever asked to be
 * resolvable.
 */
public class DnsPolicy {

    /** Address the resolver listens on inside the container. */
    public static final String LISTEN_ADDRESS = "127.0.0.1";

    /** Port the resolver listens on. */
    public static final int PORT = 53;

    /**
     * Name of the file holding the per-domain {@code server=} lines, beside the configuration.
     * <p>
     * <strong>Separate because it is the only part dnsmasq re-reads.</strong> A {@code
     * servers-file} is re-read on {@code SIGHUP}; the configuration file is not, and neither is
     * anything else. Measured against dnsmasq 2.x: a name that answered NXDOMAIN answered with
     * real addresses after a line was appended here and the process signaled - same process, no
     * restart, no window in which the container resolves nothing.
     * <p>
     * That is what lets a running task be widened by name. It costs the separation: a servers-file
     * may contain nothing but {@code server=} and {@code rev-server=}, so the {@code nftset=} lines
     * stay in the configuration and cannot be added later. The firewall half of a live widening
     * therefore goes through the clearance watcher rather than through this.
     */
    public static final String SERVERS_FILE = "dnsmasq.servers";

    /** How a refused name is written: {@code address=/<name>/}, which answers NXDOMAIN. */
    public static final String REFUSED_PREFIX = "address=/";

    private final SecurityClass securityClass;

    private final Set<String> allowedDomains = new LinkedHashSet<>();

    private final Set<String> refusedDomains = new LinkedHashSet<>();

    private final Set<String> upstreamResolvers = new LinkedHashSet<>();

    /**
     * Constructor.
     *
     * @param securityClass Decides whether anything resolves at all.
     */
    /** Domains whose answers are added to the firewall set, see {@link #autoAllow(String)}. */
    private final Set<String> autoAllowed = new java.util.LinkedHashSet<>();

    public DnsPolicy(SecurityClass securityClass) {
        this.securityClass = securityClass;
    }

    /**
     * Allows a domain and everything under it.
     *
     * @param domain Domain name, without a leading dot.
     * @return This instance.
     */
    public DnsPolicy allow(String domain) {
        allowedDomains.add(domain);
        return this;
    }

    /**
     * Adds an upstream resolver to forward allowed queries to.
     *
     * @param address Resolver address.
     * @return This instance.
     */
    /**
     * Marks a domain whose resolved addresses are added to the firewall's allow set as they are
     * looked up.
     * <p>
     * This is what a declared domain means: resolving a name and being allowed to reach it are
     * one decision, not two. Without it a declared host resolves and is then dropped, raising a
     * clearance prompt about something the definition already declared - measured on
     * {@code github.com}, dropped twenty times while the operator saw only a hang. The prompt
     * exists for what an agent reached for that nobody declared.
     * <p>
     * Resolved rather than pinned because pinning does not work: a large host rotates addresses,
     * and the address this machine resolves at task start is measurably not the one the container
     * gets a minute later. dnsmasq adds whatever it actually answered, so the set and the answer
     * cannot disagree.
     *
     * @param domain Domain to add.
     * @return This instance.
     */
    public DnsPolicy autoAllow(String domain) {
        autoAllowed.add(domain);
        return allow(domain);
    }

    /**
     * Refuses a domain and everything under it, whatever allows it.
     * <p>
     * dnsmasq takes the longest matching domain, so an {@code address=} line answering NXDOMAIN for
     * the refused name wins over the {@code server=} line that allows its parent - measured on
     * dnsmasq 2.92, with the allowance in the servers file, for a child of an allowed name and for
     * the allowed name itself, and still after a widening of it was appended and the resolver
     * signalled. A name answered NXDOMAIN never puts an address into the allow set. What it does not
     * refuse is an address: a refused host that shares one with an allowed host is reachable there.
     *
     * @param domain Domain name, without a leading dot.
     * @return This instance.
     */
    public DnsPolicy refuse(String domain) {
        refusedDomains.add(domain);
        return this;
    }

    /**
     * Returns the refused domains.
     *
     * @return Domains, in the order they were added.
     */
    public Set<String> refusedDomains() {
        return Set.copyOf(refusedDomains);
    }

    public DnsPolicy upstream(String address) {
        upstreamResolvers.add(address);
        return this;
    }

    /**
     * Returns the allowed domains.
     *
     * @return Domains, in the order they were added.
     */
    public Set<String> allowedDomains() {
        return Set.copyOf(allowedDomains);
    }

    /**
     * Renders the dnsmasq configuration.
     *
     * @return File content, ending in a line separator.
     */
    public String render(String serversFile) {

        final List<String> lines = new ArrayList<>();

        lines.add("# Generated by Sokar for security class '"
                + securityClass.name().toLowerCase() + "'. Do not edit.");
        lines.add("# Runs inside the container's network namespace.");
        lines.add("");
        lines.add("# Answer only the container, and only on loopback: the nftables chain already");
        lines.add("# permits loopback, so nothing extra has to be opened for this.");
        lines.add("listen-address=" + LISTEN_ADDRESS);
        lines.add("bind-interfaces");
        lines.add("port=" + PORT);
        lines.add("");
        lines.add("# Ignore the host's resolv.conf. The upstreams are chosen here, not inherited.");
        lines.add("no-resolv");
        lines.add("no-hosts");
        lines.add("");
        lines.add("# No cache. A cached answer would let a name keep resolving after the operator");
        lines.add("# revoked it, for as long as the TTL says.");
        lines.add("cache-size=0");
        lines.add("");
        lines.add("# Every query is logged, so a refused name shows up in the audit trail rather");
        lines.add("# than silently failing inside the agent.");
        lines.add("log-queries");
        lines.add("log-facility=-");
        lines.add("");
        lines.add("# Inside 'podman unshare' the process is root only within the user namespace:");
        lines.add("# /var/run belongs to the real root, and there is no mapped 'nobody' to drop to.");
        lines.add("# Both defaults fail there, so both are turned off.");
        lines.add("pid-file=");
        lines.add("user=root");
        lines.add("group=root");
        lines.add("");

        if (securityClass == SecurityClass.OFFLINE || upstreamResolvers.isEmpty()) {
            lines.add("# Nothing resolves. An offline project has no name to look up.");
            lines.add("address=/#/");
        } else {
            lines.add("# Everything is NXDOMAIN unless a rule below overrides it.");
            lines.add("address=/#/");
            lines.add("");
            lines.add("");
            lines.add("# The names that may resolve are in a file of their own, because that is");
            lines.add("# the only part dnsmasq re-reads on SIGHUP - which is how a running task");
            lines.add("# can be widened by name without restarting its resolver.");
            lines.add("servers-file=" + serversFile);
            if (!refusedDomains.isEmpty()) {
                lines.add("");
                lines.add("# Refused by name, whatever allows them: the longest match wins, so these");
                lines.add("# answer NXDOMAIN under an allowed parent too, and none of their addresses");
                lines.add("# reaches the firewall's allow set. Here rather than in the servers file, so");
                lines.add("# a widening of a running task cannot take one back.");
                for (final String domain : refusedDomains) {
                    lines.add(REFUSED_PREFIX + domain + "/");
                }
            }
            if (allowedDomains.isEmpty()) {
                lines.add("");
                lines.add("# No domains are allowed for this project yet.");
            } else {
                if (!autoAllowed.isEmpty()) {
                    lines.add("");
                    lines.add("# Every address answered for these is added to the firewall's allow");
                    lines.add("# set as it is answered, so what the container was told and what it");
                    lines.add("# may reach cannot drift apart. Note dnsmasq matches subdomains here.");
                    lines.add("# Both families: a declared name that answers AAAA was reachable by");
                    lines.add("# name and blocked by address, which reached the operator as a");
                    lines.add("# clearance prompt for a bare IPv6 address they could not place.");
                    for (final String domain : autoAllowed) {
                        lines.add("nftset=/" + domain
                                + "/inet#sokar#allowed_v4,inet#sokar#allowed_v6");
                    }
                }
            }
        }

        return String.join("\n", lines) + "\n";
    }

    /**
     * Returns the file the configuration points at, holding one {@code server=} line per allowed
     * name.
     * <p>
     * Written beside the configuration and re-read on {@code SIGHUP}, which is the whole reason it
     * is a file of its own. A name added here while a task runs resolves as soon as the resolver
     * is signaled; nothing is restarted and nothing else in the configuration is touched.
     *
     * @return The file's content, empty of rules when nothing may resolve.
     */
    public String renderServers() {
        final List<String> lines = new ArrayList<>();
        lines.add("# Written by sokar. One line per name this task may resolve.");
        lines.add("# Re-read when dnsmasq is signaled with SIGHUP, so a running task can be");
        lines.add("# widened without its resolver being restarted.");
        if (securityClass == SecurityClass.OFFLINE || upstreamResolvers.isEmpty()) {
            lines.add("# An offline task resolves nothing at all.");
            return String.join("\n", lines) + "\n";
        }
        for (final String domain : allowedDomains) {
            for (final String resolver : upstreamResolvers) {
                lines.add("server=/" + domain + "/" + resolver);
            }
        }
        return String.join("\n", lines) + "\n";
    }

    /**
     * Writes the configuration and its servers file, and returns the configuration's path.
     * <p>
     * The two belong together and are written together: a configuration pointing at a servers file
     * that is not there makes dnsmasq refuse to start, which reaches an operator as a container
     * that came up with no resolver at all.
     *
     * @param configFile Where the configuration goes. The servers file is written beside it.
     * @return The configuration's path, for a caller that passes it to dnsmasq.
     * @throws java.io.IOException If either file cannot be written.
     */
    public java.nio.file.Path writeTo(java.nio.file.Path configFile) throws java.io.IOException {
        final java.nio.file.Path servers = configFile.resolveSibling(SERVERS_FILE);
        java.nio.file.Files.writeString(servers, renderServers(),
                java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(configFile, render(servers.toString()),
                java.nio.charset.StandardCharsets.UTF_8);
        return configFile;
    }

    /**
     * Returns the command that runs dnsmasq with this configuration inside a container's namespace.
     *
     * @param containerPid Host process id of the container's init process.
     * @param configFile Path of the rendered configuration.
     * @return Full argument list.
     */
    public static List<String> command(long containerPid, String configFile) {
        return EgressPolicy.inNamespace(containerPid, List.of(
                "dnsmasq", "--keep-in-foreground", "--conf-file=" + configFile));
    }
}
