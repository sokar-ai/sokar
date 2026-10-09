package org.fuin.sokar.legs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The Maven executions this tree runs do something: a fixed command they give the machine tooling is one it has.
 * <p>
 * {@code exec:java@deploy} kept calling {@code sokar-machines deploy} after the tools moved that command into this
 * tree's own {@code acceptance/legs}: the build was green, and every install by the documented command answered
 * "unknown command 'deploy'".
 */
class ExecutionsTest {

    /** This tree's root, two levels up from the module. */
    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();

    static final Pattern EXECUTION = Pattern.compile("<execution>\\s*<id>([^<]+)</id>(.*?)</execution>",
            Pattern.DOTALL);

    @Test
    void everyFixedCommandAnExecutionGivesTheMachineToolingIsOneItKnows() throws Exception {
        final List<String> unknown = new ArrayList<>();
        for (final Path pom : poms()) {
            final Matcher execution = EXECUTION.matcher(Files.readString(pom, StandardCharsets.UTF_8));
            while (execution.find()) {
                final String body = execution.group(2);
                if (!body.contains("<mainClass>org.fuin.sokar.machines.Main</mainClass>")) {
                    continue;
                }
                final Matcher args = Pattern.compile("<commandlineArgs>\\s*([A-Za-z-]+)").matcher(body);
                if (args.find() && saysUnknown(args.group(1))) {
                    unknown.add(ROOT.relativize(pom) + " @" + execution.group(1) + ": " + args.group(1));
                }
            }
        }
        assertThat(unknown).as("executions naming a command sokar-machines does not have").isEmpty();
    }

    /** Runs the tooling as the pom would, with the command alone; it refuses an unknown one before anything else. */
    private static boolean saysUnknown(final String command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "org.fuin.sokar.machines.Main", command)
                .redirectErrorStream(true).start();
        process.getOutputStream().close();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        return output.contains("unknown command '" + command + "'");
    }

    static List<Path> poms() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files.filter(path -> path.endsWith("pom.xml"))
                    .filter(path -> !ROOT.relativize(path).toString().contains("target/"))
                    .filter(path -> !ROOT.relativize(path).toString().contains("node_modules/"))
                    .toList();
        }
    }
}
