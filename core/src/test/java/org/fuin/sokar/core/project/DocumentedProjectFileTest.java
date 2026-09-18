package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Reads the example in {@code doc/project-file.md} with the reader it documents.
 * <p>
 * <strong>Documentation that is not executed is documentation that drifts.</strong> Every key this
 * project file can carry was spread over five guides before this, one of them - the sources apt
 * fetches from - described nowhere at all, and a reader had no single place to find out what was
 * possible. A page that says what the file can hold is only worth having while it is true.
 * <p>
 * This parses the page's own YAML block. A key renamed in the reader, or an example that stopped
 * being valid, fails here rather than in front of somebody following it.
 */
class DocumentedProjectFileTest {

    @Test
    void theDocumentedExampleIsOneTheReaderAccepts() throws IOException {

        final String yaml = exampleFrom(Path.of("..", "doc", "project-file.md"));
        final Project project = ProjectReader.read(new StringReader(yaml), "project-file.md");

        // Every optional key, because the point of the page is the ones a wizard does not write.
        assertThat(project.name()).isEqualTo("myproject");
        assertThat(project.description()).isNotEmpty();
        assertThat(project.securityClass()).isEqualTo(SecurityClass.GUARDED);
        assertThat(project.upstream()).isNotNull();
        assertThat(project.baseImage()).isEqualTo("ubuntu:24.04");
        assertThat(project.imageSnippet()).contains("apt-get install");
        assertThat(project.declaresPackageSources()).isTrue();
        assertThat(project.effectivePackageSources()).allMatch(s -> s.startsWith("http://"));
        assertThat(project.egress().sets()).isNotEmpty();
        assertThat(project.egress().domains()).isNotEmpty();
        assertThat(project.limits().memory()).isEqualTo("8g");
        assertThat(project.limits().pids()).isEqualTo(2048);
        assertThat(project.repositoryNames())
                .containsExactly("myproject", "backend", "frontend");
        assertThat(project.repository("backend").upstream())
                .isEqualTo("git@github.com:you/backend.git");
        // The per-repository blocks, and what the page says they do: egress added, a limit
        // replacing, and a key nobody wrote falling back to the project rather than the default.
        final Repository frontend = project.repository("frontend");
        assertThat(project.egressFor(frontend).sets()).contains("maven", "nodejs");
        assertThat(project.limitsFor(frontend).memory()).isEqualTo("16g");
        assertThat(project.limitsFor(frontend).pids()).isEqualTo(project.limits().pids());
    }

    private static String exampleFrom(Path page) throws IOException {
        final Matcher block = Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL)
                .matcher(Files.readString(page));
        assertThat(block.find()).as("a yaml block in " + page).isTrue();
        return block.group(1);
    }
}
