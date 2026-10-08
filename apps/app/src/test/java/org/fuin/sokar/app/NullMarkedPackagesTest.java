package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every package that holds main code is {@code @NullMarked}.
 * <p>
 * NullAway checks only marked code, so a package without its {@code package-info.java} is skipped
 * with nothing saying so. Measured on 2026-09-28: a dereference of a {@code @Nullable} result,
 * planted in the acceptance kit, failed the build with the kit marked and compiled green without.
 */
class NullMarkedPackagesTest {

    @Test
    void everyPackageWithMainCodeIsNullMarked() throws IOException {
        final Path root = Path.of("").toAbsolutePath().getParent().getParent();

        // A wrong root finds no packages at all and would pass on nothing, so it must find this one.
        assertThat(packages(root)).as("packages found under %s", root)
                .contains("apps/app/src/main/java/org/fuin/sokar/app");
        assertThat(unmarked(root)).as("packages NullAway skips, because nothing marks them").isEmpty();
    }

    @Test
    void findsNothingToCheckUnderARootWithoutTheRepository(@org.junit.jupiter.api.io.TempDir Path root) throws IOException {
        // What the check above guards against: an empty walk, which reports no unmarked package.
        assertThat(packages(root)).isEmpty();
        assertThat(unmarked(root)).isEmpty();
    }

    @Test
    void findsAPackageWithNoPackageInfo(@org.junit.jupiter.api.io.TempDir Path root) throws IOException {
        final Path marked = Files.createDirectories(root.resolve("a/src/main/java/org/x/marked"));
        Files.writeString(marked.resolve("A.java"), "package org.x.marked;");
        Files.writeString(marked.resolve("package-info.java"), "@NullMarked\npackage org.x.marked;");
        final Path bare = Files.createDirectories(root.resolve("b/src/main/java/org/x/bare"));
        Files.writeString(bare.resolve("B.java"), "package org.x.bare;");

        assertThat(unmarked(root)).containsExactly("b/src/main/java/org/x/bare");
    }

    private static List<String> unmarked(Path root) throws IOException {
        return packages(root).stream().filter(directory -> !marked(root.resolve(directory))).toList();
    }

    private static List<String> packages(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> root.relativize(path).toString().contains("src/main/java/"))
                    .filter(path -> !root.relativize(path).toString().contains("/target/"))
                    .map(Path::getParent).distinct()
                    .map(directory -> root.relativize(directory).toString()).sorted().toList();
        }
    }

    private static boolean marked(Path directory) {
        final Path info = directory.resolve("package-info.java");
        try {
            return Files.isRegularFile(info) && Files.readString(info).contains("@NullMarked");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
