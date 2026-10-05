package org.fuin.sokar.shield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link EgressSetDirectory}.
 */
class EgressSetDirectoryTest {

    /** The sets this repository ships, checked as data rather than as a fixture copy of them. */
    private static final Path SHIPPED = Path.of("..", "egress");

    @Test
    void readsASetDeclaration() {

        final EgressSet set = EgressSetDirectory.read(new StringReader("""
                name: maven
                label: Maven
                domains:
                  - repo.maven.apache.org
                  - central.sonatype.com
                """), "test.yaml");

        assertThat(set.name()).isEqualTo("maven");
        assertThat(set.label()).isEqualTo("Maven");
        assertThat(set.domains()).containsExactly("repo.maven.apache.org", "central.sonatype.com");
    }

    @Test
    void refusesASetThatGrantsNothing() {

        // An empty set is almost certainly a half-written file, and it would resolve to nothing
        // while looking like a working declaration.
        assertThatThrownBy(() -> EgressSetDirectory.read(
                new StringReader("name: empty\ndomains: []\n"), "test.yaml"))
                .isInstanceOf(EgressSetException.class)
                .hasMessageContaining("non-empty list");
    }

    @Test
    void everySetThisRepositoryShipsParses() throws Exception {

        // The files are the product here, not an example of it: a set that does not parse is
        // installed by the package and fails on somebody's machine rather than in this build.
        assertThat(Files.isDirectory(SHIPPED)).as("%s exists", SHIPPED.toAbsolutePath()).isTrue();

        final var sets = new EgressSetDirectory(List.of(SHIPPED)).all();

        assertThat(sets).isNotEmpty();
        assertThat(sets).containsKeys("git-hosting", "python", "nodejs", "rust", "go", "containers");
        sets.forEach((name, set) -> {
            assertThat(set.domains()).as("%s grants something", name).isNotEmpty();
            assertThat(name).as("%s is named like its file", name).matches("[a-z0-9][a-z0-9-]*");
        });
    }

    @Test
    void everyShippedDomainIsOneAProjectCouldAlsoWrite() {

        // The same validation the project file goes through. A set is trusted more than a project
        // file, not less, so a set that could not be typed into project.yml is a bug in the set.
        new EgressSetDirectory(List.of(SHIPPED)).all().forEach((name, set) ->
                assertThat(new org.fuin.sokar.core.project.Egress(List.of(), set.domains()))
                        .as("set %s", name).isNotNull());
    }

    @Test
    void resolvesNamesToHostsInTheOrderDeclared() {

        final List<String> domains =
                new EgressSetDirectory(List.of(SHIPPED)).resolve(List.of("python", "nodejs"));

        assertThat(domains).startsWith("pypi.org", "files.pythonhosted.org")
                .contains("registry.npmjs.org");
    }

    @Test
    void refusesAnUnknownSetRatherThanGrantingNothing() {

        // A typo must not look like a working declaration. Silently granting nothing would send
        // the operator to debug the build instead of the project file.
        assertThatThrownBy(() -> new EgressSetDirectory(List.of(SHIPPED)).resolve(List.of("mvn")))
                .isInstanceOf(EgressSetException.class)
                .hasMessageContaining("Unknown egress set 'mvn'")
                .hasMessageContaining("Available:");
    }

    @Test
    void saysWhichSetGrantedWhichHost() {

        assertThat(new EgressSetDirectory(List.of(SHIPPED)).origins(List.of("python")))
                .containsEntry("pypi.org", "python");
    }

    @Test
    void skipsAFileItCannotReadWithoutLosingTheRest(@TempDir Path dir) throws Exception {

        Files.writeString(dir.resolve("broken.yaml"), "name: broken\ndomains: nonsense: [\n");
        Files.writeString(dir.resolve("good.yaml"), "name: good\ndomains:\n  - example.com\n");

        assertThat(new EgressSetDirectory(List.of(dir)).all()).containsOnlyKeys("good");
    }

    @Test
    void letsAnOperatorOverrideAPackagedSet(@TempDir Path mine, @TempDir Path packaged)
            throws Exception {

        Files.writeString(mine.resolve("nodejs.yaml"), "name: nodejs\ndomains:\n  - mine.example\n");
        Files.writeString(packaged.resolve("nodejs.yaml"),
                "name: nodejs\ndomains:\n  - registry.npmjs.org\n");

        assertThat(new EgressSetDirectory(List.of(mine, packaged)).resolve(List.of("nodejs")))
                .containsExactly("mine.example");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"#\"", "\"corp.example/#\"", "\"a.example\\nserver=/#/8.8.8.8\"",
        "UPPER.example", "single"})
    void aSetNamingSomethingThatIsNotAHostNameIsRefused(final String domain) {

        // Project domains were checked and set domains were not: '#' became 'server=/#/...', and every name resolved
        // and opened its ports with nobody asked.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EgressSetDirectory.read(
                new java.io.StringReader("name: x\ndomains:\n  - " + domain + "\n"), "test"))
                .isInstanceOf(EgressSetException.class);
    }
}
