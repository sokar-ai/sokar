package org.fuin.sokar.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Every job of every workflow stops after a limit of its own.
 * <p>
 * Without one a job runs until the platform's six hours: a run on a slow runner had to be cancelled by hand after
 * an hour and a half, and a leg on a rented machine pays for every minute. A job that rents a machine keeps the
 * limit of the step that rents it below the job's, so its clean-up still runs when that step times out.
 */
class WorkflowLimitsTest {

    private static final Path WORKFLOWS = Path.of("").toAbsolutePath().getParent().resolve(".github/workflows");

    @Test
    void everyJobOfEveryWorkflowHasALimit() throws IOException {
        final List<String> without = new ArrayList<>();
        for (final Path workflow : workflows()) {
            jobs(workflow).forEach((name, job) -> {
                if (!(job instanceof Map<?, ?> each && each.get("timeout-minutes") instanceof Integer minutes
                        && minutes > 0)) {
                    without.add(workflow.getFileName() + ": " + name);
                }
            });
        }
        assertThat(without).as("jobs without timeout-minutes").isEmpty();
    }

    @Test
    void aJobThatCleansUpAfterAStepLeavesItTimeToDoSo() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final Path workflow : workflows()) {
            jobs(workflow).forEach((name, job) -> {
                if (!(job instanceof Map<?, ?> each) || !(each.get("steps") instanceof List<?> steps)
                        || !(each.get("timeout-minutes") instanceof Integer limit)) {
                    return;
                }
                final boolean cleansUp = steps.stream().anyMatch(step -> step instanceof Map<?, ?> s
                        && String.valueOf(s.get("if")).contains("always()"));
                if (!cleansUp) {
                    return;
                }
                for (final Object step : steps) {
                    if (step instanceof Map<?, ?> s && s.get("run") != null && !String.valueOf(s.get("if")).contains("always()")
                            && String.valueOf(s.get("run")).contains("machines")
                            && !(s.get("timeout-minutes") instanceof Integer minutes && minutes < limit)) {
                        wrong.add(workflow.getFileName() + ": " + name + ": " + s.get("name"));
                    }
                }
            });
        }
        assertThat(wrong).as("steps that rent a machine with no limit below their job's").isEmpty();
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> found = Files.list(WORKFLOWS)) {
            final List<Path> all = found.filter(file -> file.toString().endsWith(".yml")).sorted().toList();
            assertThat(all).as("the workflows, read from " + WORKFLOWS).isNotEmpty();
            return all;
        }
    }

    private static Map<?, ?> jobs(final Path workflow) throws IOException {
        final Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(workflow));
        assertThat(document).as(workflow.toString()).isInstanceOf(Map.class);
        final Object jobs = ((Map<?, ?>) document).get("jobs");
        assertThat(jobs).as(workflow + ": jobs").isInstanceOf(Map.class);
        return (Map<?, ?>) jobs;
    }
}
