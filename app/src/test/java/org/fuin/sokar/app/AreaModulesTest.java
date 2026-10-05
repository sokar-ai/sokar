package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the area modules the command line is built from: what each must carry for the native binary.
 */
class AreaModulesTest {

    @Test
    void everyAreaWithCommandsCarriesTheirReflectionMetadata() throws IOException {

        // Without picocli's processor a module builds and passes every test on the JVM, and only the native binary
        // fails - at the first command of that area anybody runs.
        final Path root = Path.of("").toAbsolutePath().getParent();
        assertThat(withCommands(root)).as("area modules with commands under %s", root).hasSizeGreaterThan(5);
        assertThat(missingMetadata(root)).isEmpty();
    }

    @Test
    void aModuleWithCommandsAndNoMetadataIsFound(@TempDir Path root) throws IOException {
        final Path source = root.resolve("app-x/src/main/java/org/fuin/sokar/app/XCommand.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "@Command(name = \"x\") class XCommand {}");

        assertThat(missingMetadata(root)).containsExactly("app-x");
    }

    private static List<String> withCommands(Path root) throws IOException {
        final List<String> modules = new ArrayList<>();
        try (Stream<Path> all = Files.list(root)) {
            for (final Path module : all.filter(each -> each.getFileName().toString().matches("app(-[a-z]+)?"))
                    .sorted().toList()) {
                final Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sources)) {
                    if (files.filter(file -> file.toString().endsWith(".java")).anyMatch(AreaModulesTest::command)) {
                        modules.add(module.getFileName().toString());
                    }
                }
            }
        }
        return modules;
    }

    private static List<String> missingMetadata(Path root) throws IOException {
        final List<String> missing = new ArrayList<>();
        for (final String module : withCommands(root)) {
            final Path generated = root.resolve(module).resolve("target/classes/META-INF/native-image/picocli-generated");
            final boolean found;
            if (Files.isDirectory(generated)) {
                try (Stream<Path> files = Files.walk(generated)) {
                    found = files.anyMatch(file -> file.getFileName().toString().equals("reflect-config.json"));
                }
            } else {
                found = false;
            }
            if (!found) {
                missing.add(module);
            }
        }
        return missing;
    }

    private static boolean command(Path file) {
        try {
            return Files.readString(file).contains("@Command(");
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
