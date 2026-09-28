package org.fuin.sokar.core.project;

import java.util.List;

/**
 * What a project's own tooling may reach while a task runs.
 * <p>
 * A build resolves dependencies, and the registry it resolves them from is a property of the
 * project rather than of the agent working on it: the agent declares what <em>it</em> needs, this
 * declares what the <em>project's</em> tooling needs, and neither may declare the other's.
 * <p>
 * Absence means nothing, as it does for the firewall and the vault. The generous default was
 * considered and rejected: a grant that is invisible in the file, and that widens whenever a
 * release adds a set, is the wrong shape for the most dangerous key in the project file.
 *
 * <p>
 * <strong>A refusal wins over an allowance</strong>, the project's or the agent's: a name refused here
 * does not resolve inside a task even when an allowed domain is its parent. It is a refusal of a
 * name, not of an address - a host that shares an address with an allowed one is still reachable
 * at that address.
 *
 * @param sets Names of curated sets the project opts into, in the order written.
 * @param domains Hosts named directly, for the private mirror no shipped set can cover.
 * @param refused Hosts that must not resolve in a task, whatever else allows them.
 */
public record Egress(List<String> sets, List<String> domains, List<String> refused) {

    /**
     * A name that can be written into a resolver configuration without escaping it.
     * <p>
     * Deliberately stricter than DNS itself. Each declared domain becomes a {@code server=} and an
     * {@code nftset=} line in the generated dnsmasq configuration, so a value carrying a newline
     * or a slash would not be a bad host name - it would be additional configuration, written by
     * whoever could edit the project file into a file that decides what the container may reach.
     */
    private static final java.util.regex.Pattern DOMAIN =
            java.util.regex.Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?"
                    + "(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    /** A set name, matching the file that defines it. */
    private static final java.util.regex.Pattern SET =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]*");

    /**
     * Constructor with validation.
     *
     * @param sets Curated set names.
     * @param domains Host names.
     * @param refused Host names that must not resolve.
     */
    public Egress {
        sets = List.copyOf(sets);
        domains = List.copyOf(domains);
        refused = List.copyOf(refused);
        for (final String set : sets) {
            if (!SET.matcher(set).matches()) {
                throw new ProjectException("Invalid egress set name '" + set
                        + "', expected lower-case letters, digits and hyphens");
            }
        }
        for (final String domain : domains) {
            if (domain.length() > 253 || !DOMAIN.matcher(domain).matches()) {
                throw new ProjectException("Invalid egress domain '" + domain
                        + "', expected a host name such as nexus.corp.example");
            }
        }
        // The same grammar, for the same reason: a refusal becomes an 'address=' line.
        for (final String domain : refused) {
            if (domain.length() > 253 || !DOMAIN.matcher(domain).matches()) {
                throw new ProjectException("Invalid refused domain '" + domain
                        + "', expected a host name such as telemetry.example");
            }
        }
    }

    /**
     * Constructor for a declaration that refuses nothing.
     *
     * @param sets Curated set names.
     * @param domains Host names.
     */
    public Egress(List<String> sets, List<String> domains) {
        this(sets, domains, List.of());
    }

    /**
     * Returns a declaration of nothing.
     *
     * @return Empty egress.
     */
    public static Egress none() {
        return new Egress(List.of(), List.of());
    }

    /**
     * Tells whether the project declared anything at all.
     *
     * @return {@code true} when no set, domain or refusal was named.
     */
    public boolean isEmpty() {
        return sets.isEmpty() && domains.isEmpty() && refused.isEmpty();
    }
}
