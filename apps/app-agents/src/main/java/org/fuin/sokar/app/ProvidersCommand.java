package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists the model providers declared on this machine, and what the vault holds for each.
 * <p>
 * A credential is stored under the name of the provider that issued it. Until this existed the only
 * list of those names was inside {@code sokar agents --verbose}, one agent at a time, so people
 * stored a key under the agent's name instead. The rows come from {@link ProviderInventory}, which
 * the daemon serializes too.
 */
@Command(name = "providers",
        mixinStandardHelpOptions = true,
        description = "Lists the model providers declared here, and what the vault holds for each.")
public class ProvidersCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final ProviderInventory.Listing listing = ProviderInventory.list(context);

        if (listing.providers().isEmpty()) {
            out.println("no providers declared - an agent package installs one, or a file under"
                    + " ~/.local/share/sokar/providers declares one");
            out.flush();
            return 0;
        }

        final Map<String, List<String>> drivers = drivers();
        out.printf("%-12s %-24s %-34s %s%n", "NAME", "LABEL", "CREDENTIAL", "AGENTS");
        for (final Map<String, Object> row : listing.providers()) {
            final String name = row.get("name") instanceof String text ? text : "";
            out.printf("%-12s %-24s %-34s %s%n", name, row.get("label"),
                    credential(row, listing.readable()),
                    String.join(", ", drivers.getOrDefault(name, List.of("-"))));
            if (listing.readable() && (!Boolean.TRUE.equals(row.get("authenticated"))
                    || !name.equals(row.get("credentialName")))) {
                // Also when one is found under an agent's name: this is where it belongs.
                out.println("             store:   " + row.get("storeCommand"));
            }
        }
        if (!listing.readable()) {
            out.println();
            out.println("The vault is locked, so what it holds is not shown. 'sokar vault unlock'"
                    + " first.");
        }
        // Said, because the provider's name is where its key is kept: a file of a person's own that names a packaged
        // provider takes it over, and with it where the stored key goes.
        context.paths().agents().providerDirectory().shadowed().forEach((aside, won) -> out.println("set aside: " + aside
                + " - " + won + " declares the same provider, and it is the one used"));
        out.flush();
        return 0;
    }

    /**
     * Says what the vault holds for one provider.
     *
     * @param row The provider, as {@link ProviderInventory#row} makes it.
     * @param readable Whether the vault could be read.
     * @return A short description, never a value.
     */
    static String credential(Map<String, Object> row, boolean readable) {
        if (!readable) {
            return "unknown - vault locked";
        }
        if (!Boolean.TRUE.equals(row.get("authenticated"))) {
            return "none";
        }
        final String type = row.get("credentialType") instanceof String text ? text : "";
        return "stored as '" + row.get("credentialName") + "'"
                + (type.isEmpty() ? "" : " (" + type + ")");
    }

    private Map<String, List<String>> drivers() {
        final Map<String, List<String>> drivers = new LinkedHashMap<>();
        final var declared = context.providers();
        try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
            for (final var agent : agents.all()) {
                final var speaks = agent.definition().provider();
                if (speaks == null) {
                    continue;
                }
                for (final var entry : declared.entrySet()) {
                    if (speaks.canDrive(entry.getValue())) {
                        drivers.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                                .add(agent.name() + (entry.getKey().equals(
                                        speaks.defaultProvider()) ? " (default)" : ""));
                    }
                }
            }
        } catch (RuntimeException ex) {
            // Providers are still worth listing on a machine whose agents cannot be asked.
        }
        return drivers;
    }
}
