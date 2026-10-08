package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests that the code reaches the account it runs as only where it was given no other.
 */
class RealAccountTest {

    @Test
    void nothingButACommandsDefaultAndTheTwoMainsAsksForTheRealAccount() throws IOException {

        // The gate's helpers asked for this process's own account instead of the machine a command was given, so every
        // test that gave a gate command a machine of its own wrote mirrors into the developer's account - a project
        // 'uc' the operator cleared, and found again after the next build.
        final Path root = Path.of("").toAbsolutePath().getParent().getParent();
        final List<String> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (final Path module : modules.filter(each -> each.getFileName().toString().matches("app(-[a-z]+)?|daemon"))
                    .toList()) {
                final Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sources)) {
                    for (final Path file : files.filter(each -> each.toString().endsWith(".java")).toList()) {
                        final String name = file.getFileName().toString();
                        if (name.equals("SokarContext.java") || name.equals("SokarPaths.java")) {
                            continue;
                        }
                        final List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size(); i++) {
                            final String line = lines.get(i).strip();
                            if (!line.contains("SokarContext.real()") && !line.contains("SokarPaths.current()")) {
                                continue;
                            }
                            // A command's default, which the factory replaces with the machine it was given; and the
                            // two entry points, which are the real account by definition.
                            if (line.equals("private SokarContext context = SokarContext.real();")
                                    || line.contains("commandLine(SokarContext.real())")
                                    || line.equals("final SokarContext context = SokarContext.real();")
                                            && name.equals("SokarDaemon.java")) {
                                continue;
                            }
                            found.add(root.relativize(file) + ":" + (i + 1) + "  " + line);
                        }
                    }
                }
            }
        }
        assertThat(found).as("asked for the real account where a machine was given").isEmpty();
    }
}
