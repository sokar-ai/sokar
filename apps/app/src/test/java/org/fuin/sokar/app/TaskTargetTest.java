package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TaskTarget}: a task named either way, or meant by the checkout the person stands in.
 */
class TaskTargetTest {

    @TempDir
    Path dir;

    private final CommandRunner real = new ProcessCommandRunner();

    /** What podman lists: one line per task, as sokarTasks reads them. */
    private String listed = "";

    private SokarContext context;

    private Path checkout;

    @BeforeEach
    void aCheckoutInDefault() throws Exception {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final CommandRunner runner = command -> command.arguments().size() > 1
                && command.arguments().get(0).endsWith("podman") && command.arguments().get(1).equals("ps")
                ? new CommandResult(command, 0, listed, "") : real.run(command);
        context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        checkout = Files.createDirectories(dir.resolve("utils4j"));
        real.runOrFail(Command.of("git", "-C", checkout.toString(), "init", "-q"));
        new DefaultProject(context).add(checkout.toRealPath().toString(), "utils4j", checkout.toRealPath().toString(),
                "");
    }

    private void tasks(final String... names) {
        final StringBuilder lines = new StringBuilder();
        for (final String name : names) {
            final String repository = name.startsWith("sokar-default-other") ? "other" : "utils4j";
            lines.append(name).append("\tUp 1 minute\t\t\tdefault\tguarded\t").append(repository).append("\t\tid\n");
        }
        listed = lines.toString();
    }

    @Test
    void noNameInACheckoutMeansTheTaskStartedFromIt() {

        // 'sokar task status' in a checkout wanted the container's name typed out.
        tasks("sokar-default-utils4j", "sokar-default-other");

        assertThat(TaskTarget.resolve(context, null, checkout, null).container()).isEqualTo("sokar-default-utils4j");
    }

    @Test
    void eitherNameOfATaskIsTaken() {
        tasks("sokar-default-utils4j");

        assertThat(TaskTarget.resolve(context, "utils4j", dir, null).container()).isEqualTo("sokar-default-utils4j");
        assertThat(TaskTarget.resolve(context, "sokar-default-utils4j", dir, null).container())
                .isEqualTo("sokar-default-utils4j");
        assertThat(TaskTarget.resolve(context, "nobody", dir, null).container()).as("left to the command to refuse")
                .isEqualTo("nobody");
    }

    @Test
    void severalAreNamedAndAskedForOrRefusedWhereNobodyCanBeAsked() {
        tasks("sokar-default-utils4j", "sokar-default-utils4j-2");

        final TaskTarget.Found refused = TaskTarget.resolve(context, null, checkout, null);
        assertThat(refused.container()).isNull();
        assertThat(refused.code()).isEqualTo(TaskTarget.SEVERAL);
        assertThat(refused.refusal()).contains("sokar-default-utils4j").contains("sokar-default-utils4j-2");

        final List<String> asked = new java.util.ArrayList<>();
        assertThat(TaskTarget.resolve(context, null, checkout, question -> {
            asked.add(question);
            return "utils4j-2";
        }).container()).isEqualTo("sokar-default-utils4j-2");
        assertThat(asked).singleElement().asString().contains("Which one?");
    }

    @Test
    void noneStartedFromTheCheckoutIsSaidAndOutsideACheckoutANameIsAskedFor() throws Exception {
        tasks("sokar-default-other");

        final TaskTarget.Found none = TaskTarget.resolve(context, null, checkout, null);
        assertThat(none.container()).isNull();
        assertThat(none.code()).isEqualTo(TaskTarget.NONE);
        assertThat(none.refusal()).contains("no task was started from");

        final TaskTarget.Found outside = TaskTarget.resolve(context, null, Files.createDirectories(dir.resolve("plain")),
                null);
        assertThat(outside.container()).isNull();
        assertThat(outside.refusal()).contains("name a task");
    }
}
