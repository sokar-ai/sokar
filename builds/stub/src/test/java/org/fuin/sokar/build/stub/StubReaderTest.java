package org.fuin.sokar.build.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.build.api.Build;
import org.fuin.sokar.build.api.BuildRefused;
import org.fuin.sokar.build.api.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StubReaderTest {

    private static final String SHA = "4f2a".repeat(10);

    private static final Target TARGET = new Target("git@forge.example:o/n.git", "", "vault-token");

    @TempDir
    Path directory;

    @Test
    void answersFromItsFileReadAgainOnEveryCall() throws IOException {
        final StubReader stub = new StubReader(answers("""
                {"heads": {"fix-login": "%s"}, "builds": {"%s": {"verdict": "running"}}}""".formatted(SHA, SHA)));

        assertThat(stub.head(TARGET, "fix-login")).isEqualTo(SHA);
        assertThat(stub.head(TARGET, "other")).isEmpty();
        assertThat(stub.look(TARGET, SHA, Build.Logs.FAILURE).verdict()).isEqualTo(Build.RUNNING);

        answers("""
                {"builds": {"%s": {"verdict": "failure", "jobs": [{"name": "Build / test", "result": "failure",
                 "log": "boom"}, {"name": "Build / lint", "result": "success", "log": "fine"}]}}}""".formatted(SHA));
        final Build failed = stub.look(TARGET, SHA, Build.Logs.FAILURE);
        assertThat(failed.verdict()).as("what the test wrote since").isEqualTo(Build.FAILURE);
        assertThat(failed.jobs()).extracting(Build.Job::logText).as("every job, the failed one's log only")
                .containsExactly("boom", "");
        assertThat(stub.look(TARGET, SHA, Build.Logs.ALL).jobs()).extracting(Build.Job::logText)
                .as("every job's log when all are wanted").containsExactly("boom", "fine");
        assertThat(stub.look(TARGET, "0".repeat(40), Build.Logs.FAILURE).verdict())
                .as("a commit it holds nothing for")
                .isEqualTo(Build.UNKNOWN);
    }

    @Test
    void acceptsOnlyTheTokenItsFileNames() throws IOException {
        final StubReader stub = new StubReader(answers("{\"token\": \"vault-token\", \"heads\": {\"m\": \"" + SHA + "\"}}"));

        assertThat(stub.head(TARGET, "m")).isEqualTo(SHA);
        assertThatThrownBy(() -> stub.head(new Target(TARGET.upstream(), "", "another"), "m"))
                .isInstanceOfSatisfying(BuildRefused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(BuildRefused.Reason.CREDENTIAL_REFUSED));
    }

    @Test
    void refusesEveryCallAsItsFileSays() throws IOException {
        final StubReader stub = new StubReader(answers(
                "{\"refuse\": {\"reason\": \"RATE_LIMITED\", \"detail\": \"slow down\", \"retryAfter\": 7}}"));

        assertThatThrownBy(() -> stub.look(TARGET, SHA, Build.Logs.FAILURE)).isInstanceOfSatisfying(BuildRefused.class,
                refused -> {
            assertThat(refused.reason()).isEqualTo(BuildRefused.Reason.RATE_LIMITED);
            assertThat(refused.retryAfterSeconds()).isEqualTo(7);
        });
    }

    @Test
    void findsItsAnswersBesideItselfUnlessTheEnvironmentNamesThem() {
        assertThat(StubReader.answers(null, "/home/u/.local/share/sokar/builds/" + StubReader.FORGE))
                .isEqualTo(Path.of("/home/u/.local/share/sokar/builds/answers.json"));
        assertThat(StubReader.answers("/tmp/answers.json", "/anywhere/" + StubReader.FORGE)).isEqualTo(Path.of("/tmp/answers.json"));
        assertThatThrownBy(() -> new StubReader(directory.resolve("missing.json"))
                .look(TARGET, SHA, Build.Logs.FAILURE))
                .isInstanceOfSatisfying(BuildRefused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(BuildRefused.Reason.UNREACHABLE));
    }

    private Path answers(final String json) throws IOException {
        return Files.writeString(directory.resolve("stub.json"), json, StandardCharsets.UTF_8);
    }
}
