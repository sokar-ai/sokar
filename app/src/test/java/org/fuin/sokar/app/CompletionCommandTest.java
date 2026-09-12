package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The completion scripts, and the one thing about them a unit test can get wrong.
 */
class CompletionCommandTest {

    /** Where the native image is told which resources to carry. */
    private static final Path METADATA = Path.of("src/main/resources/META-INF/native-image",
            "org.fuin.sokar/sokar-app/reachability-metadata.json");

    @Test
    void everyScriptIsDeclaredToTheNativeImage() throws IOException {

        // This is the defect it was written for, and it was found by running the binary rather
        // than by any test: the resources were on the classpath, every test passed, and
        // 'sokar completion bash' in the native image answered "this build carries no bash
        // completion script". A resource nobody declares is simply absent from the image, and
        // nothing in a JVM test can see that.
        final String metadata = Files.readString(METADATA, StandardCharsets.UTF_8);
        for (final String shell : List.of("bash", "zsh")) {
            final String resource = shell.equals("bash") ? "completion/sokar" : "completion/_sokar";
            assertThat(metadata)
                    .as("%s script is not declared in %s, so the native image will not carry it",
                            shell, METADATA)
                    .contains(resource);
        }
    }

    @Test
    void everyDeclaredScriptIsActuallyThere() throws IOException {

        // The other direction: a declaration for a file that does not exist is a build that
        // silently ships nothing, which is the same failure with a different cause.
        for (final String resource : List.of("/completion/sokar", "/completion/_sokar")) {
            try (InputStream script = CompletionCommand.class.getResourceAsStream(resource)) {
                assertThat(script).as("%s is missing", resource).isNotNull();
                assertThat(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                        .as("%s is empty", resource).isNotBlank();
            }
        }
    }

    @Test
    void bothScriptsPassThePartialWordQuoted() throws IOException {

        // Measured on zsh 5.9: unquoted, an empty partial word - which is what TAB after a space
        // means, and so most of the times anybody presses it - is dropped from the argument list
        // entirely, and the last real word is taken as the word being completed. 'sokar task
        // <TAB>' then answered 'task'. The bash half was right by accident and is pinned here so
        // it stays that way.
        for (final String resource : List.of("/completion/sokar", "/completion/_sokar")) {
            try (InputStream script = CompletionCommand.class.getResourceAsStream(resource)) {
                final String text = new String(script.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(text).as("%s must quote the partial word", resource)
                        .containsAnyOf("\"${COMP_WORDS[$COMP_CWORD]}\"", "\"${words[CURRENT]}\"");
            }
        }
    }

    @Test
    void theScriptsOnlyEverAskAndNeverAct() {

        // Completion runs on every TAB, including TABs somebody did not mean. The only sokar
        // command either script may run is the one that reads.
        //
        // Comments are stripped first, and that is not a detail: both scripts explain the
        // callback in their header, so counting every line that names it counted the prose as a
        // second call. The first version of this test did exactly that and failed on correct
        // scripts - it was caught by the full suite after a narrower run had reported success.
        for (final String resource : List.of("/completion/sokar", "/completion/_sokar")) {
            final List<String> code = read(resource).lines()
                    .map(String::strip).filter(line -> !line.startsWith("#")).toList();
            // Counted rather than matched, and that took three tries to get right. What has to
            // be true is "this script runs the binary once, and that once is the callback" - and
            // a test that merely looked for '__complete' somewhere passed a script that ran
            // 'task list' first and the callback after, which is exactly the thing forbidden.
            // The token that RUNS the binary is the array's element zero; the other COMP_WORDS
            // expansions are the words being passed to it.
            final String body = String.join("\n", code);
            final String call = resource.endsWith("_sokar") ? "${words[1]}" : "${COMP_WORDS[0]}";
            assertThat(occurrences(body, call))
                    .as("%s runs sokar %d times; completion runs it once, to ask", resource,
                            occurrences(body, call))
                    .isEqualTo(1);
            // In bash the expansion is quoted, so the closing quote sits between the two.
            final String callThenVerb = resource.endsWith("_sokar")
                    ? "${words[1]} __complete" : "${COMP_WORDS[0]}\" __complete";
            assertThat(body).as("%s runs sokar as something other than the read-only callback",
                    resource).contains(callThenVerb);
        }
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int at = text.indexOf(token);
        while (at >= 0) {
            count++;
            at = text.indexOf(token, at + token.length());
        }
        return count;
    }

    private String read(String resource) {
        try (InputStream script = CompletionCommand.class.getResourceAsStream(resource)) {
            return new String(script.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(resource, ex);
        }
    }
}
