package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TaskNamesBothWaysTest {

    /** A command's first parameter, and its label. */
    private static final Pattern FIRST = Pattern.compile("@Parameters\\(index = \"0\"[^)]*paramLabel = \"([^\"]+)\"");

    /** Run by a task's launch with the container's name, never typed by a person. */
    private static final List<String> LAUNCHED = List.of("TaskWatchBuildsCommand.java");

    @Test
    void everyTaskAndTalkCommandTakesATaskByItsContainersNameOrItsOwn() throws IOException {
        // 'sokar task files b37online' said nothing was handed to it, while the container's name listed six records:
        // every other task command took both spellings, and files, give, take-back, label, clearance and talk did not.
        final Path apps = Path.of("").toAbsolutePath().getParent();
        final List<String> missing = new ArrayList<>();
        int found = 0;
        try (Stream<Path> sources = Files.walk(apps)) {
            for (final Path source : sources.filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> path.getFileName().toString().matches("(Task|Talk)[A-Za-z]*Command\\.java"))
                    .filter(path -> !LAUNCHED.contains(path.getFileName().toString())).toList()) {
                final String text = Files.readString(source);
                final Matcher first = FIRST.matcher(text.replace("\n", " "));
                if (!first.find() || !first.group(1).matches("(?i)<?task>?")) {
                    continue;
                }
                found++;
                if (!text.contains("TaskTarget.")) {
                    missing.add(source.getFileName().toString());
                }
            }
        }

        assertThat(found).as("task and talk commands that take a task").isGreaterThan(20);
        assertThat(missing).as("commands that take only the container's name").isEmpty();
    }
}
