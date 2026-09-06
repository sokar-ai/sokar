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
 * @param sets Names of curated sets the project opts into, in the order written.
 * @param domains Hosts named directly, for the private mirror no shipped set can cover.
 */
public record Egress(List<String> sets, List<String> domains) {

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
     */
    public Egress {
        sets = List.copyOf(sets);
        domains = List.copyOf(domains);
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
     * @return {@code true} when neither a set nor a domain was named.
     */
    public boolean isEmpty() {
        return sets.isEmpty() && domains.isEmpty();
    }
}
