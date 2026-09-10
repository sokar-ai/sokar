package org.fuin.sokar.acceptance;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestRunFinished;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports scenarios where GitHub shows them, rather than in a log somebody has to open.
 * <p>
 * <strong>Two surfaces, and they answer different questions.</strong> An <em>annotation</em> is
 * attached to the line of the scenario that failed, so a failure points at the sentence that
 * describes it instead of at a stack frame. A <em>job summary</em> is the whole run on the
 * workflow page - what ran, what passed, and how long the slow ones took - which is the overview
 * that was asked for when this module was proposed.
 * <p>
 * <strong>Silent everywhere else.</strong> Both are driven by environment variables GitHub sets;
 * with neither present this writes nothing at all, so a local run is not full of workflow
 * commands nobody can see.
 */
public final class GitHubReport implements ConcurrentEventListener {

    /**
     * Where feature files live, so an annotation points at the file in the repository.
     * <p>
     * A property because this runs from repositories whose layout is their own: an annotation
     * carrying a path that does not exist there points at nothing, and GitHub shows it against
     * no line rather than saying the path was wrong. The default is a single-module repository;
     * this one's suite sets its own.
     */
    private static final String FEATURES =
            System.getProperty("sokar.acceptance.features", "src/test/resources/");

    private final Map<String, List<Case>> byFeature = new LinkedHashMap<>();

    /** Constructor for Cucumber, which instantiates a plugin by its class name. */
    public GitHubReport() {
        super();
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseFinished.class, this::finished);
        publisher.registerHandlerFor(TestRunFinished.class, this::runFinished);
    }

    private synchronized void finished(TestCaseFinished event) {
        final String feature = feature(event.getTestCase().getUri().toString());
        final Status status = event.getResult().getStatus();
        byFeature.computeIfAbsent(feature, key -> new ArrayList<>()).add(new Case(
                event.getTestCase().getName(), status,
                event.getResult().getDuration().toMillis()));
        if (status != Status.FAILED) {
            return;
        }
        // On the line of the scenario, not of the step: the scenario is the sentence somebody
        // wrote, and it is what they have to change.
        annotate(feature, event.getTestCase().getLocation().getLine(),
                event.getTestCase().getName(),
                event.getResult().getError() == null ? "failed"
                        : String.valueOf(event.getResult().getError().getMessage()));
    }

    private static void annotate(String feature, int line, String scenario, String message) {
        if (System.getenv("GITHUB_ACTIONS") == null) {
            return;
        }
        // Workflow commands are one line each: a newline would end the command and print the rest
        // as ordinary output, which is how a multi-line failure turns into half an annotation.
        System.out.println(annotation(feature, line, scenario, message));
    }

    /**
     * Builds the workflow command for one failed scenario.
     *
     * @param feature Repository-relative feature file.
     * @param line Line of the scenario.
     * @param scenario Its name.
     * @param message Why it failed.
     * @return One line GitHub attaches to that file and line.
     */
    static String annotation(String feature, int line, String scenario, String message) {
        return "::error file=" + property(feature) + ",line=" + line
                + ",title=" + property(scenario) + "::" + message(message);
    }

    /** Escapes a property of a workflow command. */
    private static String property(String text) {
        return message(text).replace(",", "%2C").replace(":", "%3A");
    }

    /**
     * How much of a failure fits in an annotation.
     * <p>
     * A step that asserts on a command's whole output puts that whole output in the message -
     * measured at over a kilobyte for one 'doctor' assertion. GitHub caps an annotation, so an
     * uncapped message is one that arrives truncated somewhere nobody chose. The full text is in
     * the run log and in the HTML report either way; what this has to carry is enough to
     * recognise which failure it is.
     */
    private static final int ANNOTATION_LIMIT = 900;

    /** Escapes the message of a workflow command, and keeps it to a size that survives. */
    private static String message(String text) {
        final String capped = text.length() <= ANNOTATION_LIMIT ? text
                : text.substring(0, ANNOTATION_LIMIT) + " ... (see the run log)";
        return capped.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    private synchronized void runFinished(TestRunFinished event) {
        final String file = System.getenv("GITHUB_STEP_SUMMARY");
        if (file == null) {
            return;
        }
        try {
            Files.writeString(Path.of(file), summary(byFeature), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            // A summary that cannot be written is not a reason to fail a run that passed.
            System.out.println("could not write the job summary: " + ex.getMessage());
        }
    }

    /**
     * Builds the whole run as it appears on the workflow page.
     * <p>
     * <strong>Written whether the run was green or red.</strong> Failures also arrive as
     * annotations, so a red run says what broke either way; a green one has nothing but this,
     * and without it the page cannot distinguish a suite that passed from a suite that never
     * ran.
     * <p>
     * <strong>An empty run says so rather than writing nothing.</strong> Silence reads as "no
     * acceptance step", which is indistinguishable from a step that ran and matched no
     * scenarios - a tag expression that excludes everything, or a module missing from the
     * reactor. Both have happened here.
     *
     * @param byFeature What ran, in the order it ran.
     * @return Markdown for the job summary.
     */
    static String summary(Map<String, List<Case>> byFeature) {
        if (byFeature.isEmpty()) {
            return "## Acceptance\n\n**No scenarios ran.** Nothing was selected - check the "
                    + "tag filter and that the suite is in the reactor.\n";
        }
        final StringBuilder summary = new StringBuilder("## Acceptance\n\n");
        summary.append("Run against a real machine, over a real terminal.\n\n");
        summary.append("| | Feature | Scenario | | Time |\n|---|---|---|---|---:|\n");
        int failed = 0;
        int skipped = 0;
        int total = 0;
        for (final Map.Entry<String, List<Case>> entry : byFeature.entrySet()) {
            // Grouped by the name Cucumber reports, which folds an outline's examples into one
            // row - ten identical rows is a summary nobody reads to the end. It folds only while
            // the outline's *name* carries no <placeholders>: with them every example gets a
            // distinct name and lands on its own row. Every outline in this repository is
            // placeholder-free, so they fold here; a repository using the kit may see otherwise.
            final Map<String, List<Case>> grouped = new LinkedHashMap<>();
            for (final Case each : entry.getValue()) {
                grouped.computeIfAbsent(each.name(), key -> new ArrayList<>()).add(each);
            }
            for (final Map.Entry<String, List<Case>> row : grouped.entrySet()) {
                final List<Case> runs = row.getValue();
                final long bad = runs.stream().filter(each -> each.status() == Status.FAILED)
                        .count();
                final long past = runs.stream().filter(each -> each.status() == Status.SKIPPED)
                        .count();
                final long time = runs.stream().mapToLong(Case::millis).sum();
                total += runs.size();
                failed += bad;
                skipped += past;
                summary.append("| ").append(bad > 0 ? mark(Status.FAILED)
                                : mark(runs.getFirst().status()))
                        .append(" | `").append(shortName(entry.getKey()))
                        .append("` | ").append(row.getKey())
                        .append(" | ").append(runs.size() == 1 ? ""
                                : (runs.size() - bad) + "/" + runs.size())
                        .append(" | ").append(time).append("ms |\n");
            }
        }
        // Skipped is neither passed nor failed, and counting it as passed is the summary saying
        // something untrue: a suite whose credential half never ran because a secret was absent
        // reported every scenario green. That absence is a supported state - a fork has no secret
        // and the run is meant to degrade rather than go red - so it is the state most likely to
        // be read, and it has to say what it proved and not more.
        summary.append("\n**").append(total - failed - skipped).append(" of ").append(total)
                .append(" passed.**");
        if (skipped > 0) {
            summary.append(" ").append(skipped).append(" skipped, so this run proved less than a"
                    + " full one.");
        }
        if (failed > 0) {
            summary.append(" ").append(failed).append(" failed.");
        }
        summary.append("\n");
        return summary.toString();
    }

    /**
     * Returns the feature as somebody refers to it, rather than as the classpath holds it.
     *
     * @param feature The path.
     * @return The last two segments.
     */
    private static String shortName(String feature) {
        final String[] parts = feature.split("/");
        return parts.length < 2 ? feature
                : parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private static String mark(Status status) {
        return switch (status) {
            case PASSED -> ":white_check_mark:";
            case FAILED -> ":x:";
            case SKIPPED -> ":fast_forward:";
            default -> "-";
        };
    }

    /**
     * Turns a classpath uri back into the file in the repository.
     *
     * @param uri What Cucumber reports.
     * @return The path an annotation can name.
     */
    static String feature(String uri) {
        final int at = uri.indexOf("classpath:");
        return at < 0 ? uri : FEATURES + uri.substring(at + "classpath:".length());
    }

    /** One scenario's outcome. */
    record Case(String name, Status status, long millis) {
    }
}
