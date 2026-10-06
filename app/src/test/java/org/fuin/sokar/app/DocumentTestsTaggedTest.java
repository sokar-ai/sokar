package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests that every test reading the repository's documents carries the {@code documents} tag.
 * <p>
 * A change to documents or issues runs only the tests so tagged; one that reads a document without the tag would
 * be left out of that run, and the run would stay green while the document it checks is broken.
 */
@Tag("documents")
class DocumentTestsTaggedTest {

    /** How a test reaches the documents: a page under {@code doc/}, the site's navigation, or every Markdown page. */
    private static final Pattern READS_DOCUMENTS =
            Pattern.compile("\"\\.\\./doc|\"\\.\\.\",\\s*\"doc\"|mkdocs\\.yml|endsWith\\(\"\\.md\"\\)");

    private static final String TAG = "@Tag(\"documents\")";

    private static List<Path> testsReadingDocuments() throws Exception {
        try (Stream<Path> sources = Files.walk(Path.of(".."))) {
            return sources.filter(path -> path.toString().contains("/src/test/java/"))
                    .filter(path -> path.getFileName().toString().endsWith("Test.java"))
                    .filter(path -> READS_DOCUMENTS.matcher(read(path)).find())
                    .sorted()
                    .toList();
        }
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void everyTestThatReadsADocumentIsTagged() throws Exception {
        final List<Path> found = testsReadingDocuments();

        // A guard that finds nothing passes forever: assert what it found, then what it checks.
        assertThat(found).extracting(path -> path.getFileName().toString())
                .as("tests found reading documents")
                .contains("ReachDocumentTest.java",
                        "CommandsDocumentedTest.java", "MailboxGuideTest.java", "DocumentedProjectFileTest.java");
        assertThat(found).filteredOn(path -> !read(path).contains(TAG))
                .as("tests that read a document without %s", TAG)
                .isEmpty();
    }
}
