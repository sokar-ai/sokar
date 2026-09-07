package org.fuin.sokar.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps Sokar from knowing which agents exist.
 * <p>
 * The point of the agent modules is that the dependency arrow runs one way: an agent knows about
 * Sokar, Sokar does not know about agents. That is easy to state, easy to believe, and easy to
 * break by accident - so it is checked rather than trusted.
 * <p>
 * The reference implementation shows exactly how it breaks. Its {@code claude.yaml} declares
 * {@code capabilities.log_format: claude-stream-json}, the roster parses it into a typed field and
 * carries it faithfully to the code that needs it, and then that code ignores it and writes
 * {@code if effective_agent == "claude"} instead. A thirteenth agent declaring the same capability
 * would be shown as plain text, silently. These two rules are what would have caught that.
 */
class AgentIsolationTest {

    /** Package holding the agent API, which everything is allowed to depend on. */
    private static final String API = "org.fuin.sokar.agent.api";

    /** Everything under here is agent-shaped and exempt from both rules. */
    private static final String AGENTS_DIRECTORY = "agents";

    /** Where provider declarations live; they are allowed to name themselves. */
    private static final String PROVIDERS_DIRECTORY = "providers";

    @Test
    void noCodeOutsideTheAgentModulesDependsOnAnAgent() {

        final JavaClasses classes = new ClassFileImporter().importPackages("org.fuin.sokar");

        final ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("org.fuin.sokar.agent..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.fuin.sokar.agent.impl..")
                .because("an agent is discovered through the agent API, never named. Depending"
                        + " on one"
                        + " here would make adding the next agent a change to this module")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void onlyTheSpiIsVisibleOutsideTheAgentModules() {

        final JavaClasses classes = new ClassFileImporter().importPackages("org.fuin.sokar.app");

        final ArchRule rule = noClasses()
                .should().dependOnClassesThat(
                        com.tngtech.archunit.base.DescribedPredicate.describe(
                                "an agent package other than " + API,
                                javaClass -> javaClass.getPackageName().startsWith("org.fuin.sokar.agent.")
                                        && !javaClass.getPackageName().equals(API)))
                .because("sokar-app is the composition root: it may call the agent API and must"
                        + " not know"
                        + " what implements it")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void noAgentNameAppearsAsAStringLiteralOutsideTheAgentModules() throws IOException {

        // ArchUnit reads types and members, not constant-pool literals, so this one is a source
        // scan. It is the rule that catches `if name == "claude"`, which is the failure mode that
        // no dependency rule can see.
        final Path root = repositoryRoot();
        final List<String> agentNames = agentNames(root);

        if (agentNames.isEmpty()) {
            // Before the first agent exists there is nothing to find, and a scan that can never
            // fail should say so rather than pass quietly.
            assertThat(root.resolve(AGENTS_DIRECTORY)).exists();
            return;
        }

        final List<String> offences = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(root)) {
            sources.filter(AgentIsolationTest::isProductSource)
                    .filter(path -> !root.relativize(path).startsWith(AGENTS_DIRECTORY))
                    .forEach(path -> scan(root, path, agentNames, offences));
        }

        assertThat(offences)
                .as("agent names used as string literals outside %s/", AGENTS_DIRECTORY)
                .isEmpty();
    }

    @Test
    void noProviderNameAppearsAsAStringLiteralInJava() throws IOException {

        // The same rule as for agents, for the same reason: a provider is selected by name at run
        // time, so `if (provider.equals("..."))` in Sokar would make adding the next one a change
        // to this codebase.
        //
        // Narrower than the agent rule in one way, and deliberately. That rule scans tests too,
        // because an agent has a module of its own to be exempt. A provider has no module - it is
        // a YAML file and nothing else - so a test that checks the shipped declarations has
        // nowhere to live that is exempt, and would have to describe them without naming them.
        // That would test less. Product code is where the rule has teeth, so that is what is
        // scanned.
        //
        // Declarations themselves may name providers: an agent says which it uses by default,
        // which is data, and moving that into data is the whole point.
        final Path root = repositoryRoot();
        final List<String> providerNames = providerNames(root);

        if (providerNames.isEmpty()) {
            assertThat(root.resolve(PROVIDERS_DIRECTORY)).exists();
            return;
        }

        final List<String> offences = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(root)) {
            sources.filter(AgentIsolationTest::isProductSource)
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .forEach(path -> scan(root, path, providerNames, offences));
        }

        assertThat(offences)
                .as("provider names used as string literals in Java")
                .isEmpty();
    }

    /**
     * Returns the providers this repository declares, taken from the files themselves so the rule
     * needs no maintaining when one is added.
     *
     * @param root Repository root.
     * @return Names, sorted.
     * @throws IOException If the directory cannot be read.
     */
    private static List<String> providerNames(Path root) throws IOException {
        final Path providers = root.resolve(PROVIDERS_DIRECTORY);
        if (!Files.isDirectory(providers)) {
            return List.of();
        }
        try (Stream<Path> declarations = Files.list(providers)) {
            return declarations
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .map(path -> path.getFileName().toString().replace(".yaml", ""))
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    /**
     * Tells whether a path is Java this project builds.
     * <p>
     * Scoped to Maven source roots rather than every {@code .java} file under the repository. The
     * first version was not, and reported a spike probe under {@code .sokar/} - a scratch file
     * from Phase 0 that is not compiled, not shipped, and not subject to any of this.
     */
    private static boolean isProductSource(Path path) {
        final String name = path.toString();
        return name.endsWith(".java")
                && !name.contains("/target/")
                && (name.contains("/src/main/java/") || name.contains("/src/test/java/"));
    }

    private static void scan(Path root, Path file, List<String> agentNames, List<String> offences) {
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                if (line.strip().startsWith("*") || line.strip().startsWith("//")) {
                    // Prose may name an agent; only code may not.
                    continue;
                }
                for (final String name : agentNames) {
                    if (line.contains('"' + name + '"')) {
                        offences.add(root.relativize(file) + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + file, ex);
        }
    }

    /**
     * Returns the agents this repository has, taken from the definition files themselves so the
     * rule needs no maintaining when one is added.
     */
    private static List<String> agentNames(Path root) throws IOException {
        final Path agents = root.resolve(AGENTS_DIRECTORY);
        if (!Files.isDirectory(agents)) {
            return List.of();
        }
        try (Stream<Path> definitions = Files.walk(agents)) {
            return definitions
                    // Not target/: a built module holds a copy of its own definition, and counting
                    // it would report every offense twice.
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equals("agent"))
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .map(path -> path.getFileName().toString().replace(".yaml", ""))
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private static Path repositoryRoot() {
        // Surefire runs with the module directory as the working directory.
        return Path.of("").toAbsolutePath().getParent();
    }
}
