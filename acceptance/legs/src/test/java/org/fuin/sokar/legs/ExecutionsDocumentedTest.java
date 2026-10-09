package org.fuin.sokar.legs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every Maven execution a document tells a person to run is one of this tree's.
 * <p>
 * {@code AGENTS.md} and {@code doc/build.md} kept naming {@code exec:java@deploy} for installing on a machine,
 * after the command it ran had left the build tools for this tree's {@code acceptance/legs}.
 */
@Tag("documents")
class ExecutionsDocumentedTest {

    private static final Path ROOT = ExecutionsTest.ROOT;

    private static final Pattern NAMED = Pattern.compile("exec:(?:java|exec)@([A-Za-z0-9-]+)");

    @Test
    void everyExecutionTheDocumentsNameIsOneOfThisTree() throws IOException {
        final Set<String> executions = new LinkedHashSet<>();
        for (final Path pom : ExecutionsTest.poms()) {
            final Matcher execution = ExecutionsTest.EXECUTION.matcher(Files.readString(pom, StandardCharsets.UTF_8));
            while (execution.find()) {
                executions.add(execution.group(1).strip());
            }
        }
        final List<String> missing = new ArrayList<>();
        for (final Path document : documents()) {
            final Matcher named = NAMED.matcher(Files.readString(document, StandardCharsets.UTF_8));
            while (named.find()) {
                if (!executions.contains(named.group(1))) {
                    missing.add(ROOT.relativize(document) + ": " + named.group());
                }
            }
        }
        assertThat(missing).as("executions the documents name and no pom has").isEmpty();
    }

    private static List<Path> documents() throws IOException {
        final List<Path> documents = new ArrayList<>(List.of(ROOT.resolve("AGENTS.md"), ROOT.resolve("README.md")));
        try (Stream<Path> pages = Files.walk(ROOT.resolve("doc"))) {
            pages.filter(path -> path.toString().endsWith(".md")).forEach(documents::add);
        }
        return documents.stream().filter(Files::isRegularFile).toList();
    }
}
