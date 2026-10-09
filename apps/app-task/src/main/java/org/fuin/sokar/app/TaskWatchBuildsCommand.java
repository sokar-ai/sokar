package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.build.api.Build;
import org.fuin.sokar.build.api.InstalledBuildReader;
import org.fuin.sokar.build.api.Target;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * The helper that tells an {@code online} task what the build of its push did. A task's launch starts it; it asks the
 * forge from the host, with the vault's credential, and hands each verdict into the task.
 */
@Command(name = "watch-builds",
        mixinStandardHelpOptions = true,
        description = "Runs beside an online task: follows what it pushes and hands each build's verdict into"
                + " /sokar/files. Started by the task's launch.")
public class TaskWatchBuildsCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "TASK", description = "The task, as 'sokar task list' shows it.")
    private String container;

    @Option(names = "--branch", paramLabel = "<name>", required = true,
            description = "The branch the task pushes to.")
    private String branch;

    @Option(names = "--upstream", paramLabel = "<url>", required = true,
            description = "The repository it pushes to, as the project file names it.")
    private String upstream;

    @Option(names = "--forge", paramLabel = "<name>", required = true,
            description = "The build reader to ask, as the project file names it under builds.forge.")
    private String forge;

    @Option(names = "--api", paramLabel = "<url>", description = "The forge's API, when it is not the forge's own.")
    private String api = "";

    @Option(names = "--logs", paramLabel = "<which>",
            description = "Which jobs' logs reach the task: failure (each failed one) or all. Default: ${DEFAULT-VALUE}")
    private String logs = "failure";

    @Option(names = "--credential", paramLabel = "<entry>", required = true,
            description = "The vault entry the forge is read with; its value never leaves this process.")
    private String credential;

    @Option(names = "--pid-file", paramLabel = "<file>",
            description = "Writes this process's id here, so the poststop hook can reap it.")
    private @Nullable Path pidFile;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter err = spec.commandLine().getErr();
        if (pidFile != null) {
            try {
                org.fuin.sokar.wire.HelperPid.record(pidFile);
            } catch (IOException ex) {
                err.println("sokar: cannot write " + pidFile + ": " + ex.getMessage());
                return 1;
            }
        }
        // Started by hand at a terminal it asks once, as 'sokar vault unlock' would: it reads the credential for as long
        // as it watches. Started for a task it has no terminal and waits for the vault as before.
        context.openIfShut(true, true, err);
        final TaskPaths paths = context.paths().tasks();
        final Path state = paths.containerState(container);
        final Path executable;
        try {
            executable = InstalledBuildReader.find(forge, List.of(context.paths().xdg().data().resolve("builds"),
                    SokarPaths.packaged(InstalledBuildReader.PACKAGED)));
        } catch (RuntimeException ex) {
            err.println("sokar: " + ex.getMessage());
            said(paths, String.valueOf(ex.getMessage()));
            return 1;
        }
        final HandIns handIns = HandIns.of(context);
        final Build.Logs policy = Build.Logs.of(logs);
        try (InstalledBuildReader reader = new InstalledBuildReader(executable, forge,
                state.resolve("builds.sock"))) {
            final BuildWatch.Forge asked = new BuildWatch.Forge() {
                @Override
                public String head(final String name) {
                    return reader.head(target(), name);
                }

                @Override
                public Build look(final String commit) {
                    return reader.look(target(), commit, policy);
                }
            };
            new BuildWatch(asked, (name, file) -> handIns.give(container, name, file, HandIns.SOKAR),
                    new TaskBuilds(paths), container, HandIns.runOf(paths, container), branch,
                    state.resolve("builds"), Clock.systemUTC(),
                    duration -> {
                        // After each round, so a verdict and the logs it names arrive before the line naming them.
                        new AgentWake(context).announceFiles(container);
                        waitFor(duration, () -> vaultStamp(), step -> Thread.sleep(step.toMillis()));
                    }, why -> {
                        err.println(why.isEmpty() ? "sokar: the forge answers" : "sokar: " + why);
                        err.flush();
                        said(paths, why);
                    }).run(() -> Files.isDirectory(state));
            return 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (RuntimeException ex) {
            // A reader that will not start or speaks another version: said where a person looks, not only here.
            err.println("sokar: " + ex.getMessage());
            said(paths, String.valueOf(ex.getMessage()));
            return 1;
        }
    }

    /** How often a wait looks whether the vault changed. */
    static final Duration STEP = Duration.ofSeconds(5);

    /**
     * Waits, but no longer than until the vault changes: a credential replaced after the forge refused it is then
     * asked with at once, not after the wait for a person to fix it.
     *
     * @param total How long to wait at most.
     * @param stamp What the vault looks like now; any change ends the wait.
     * @param sleep How a step is waited.
     * @throws InterruptedException When the watch is to end.
     */
    static void waitFor(final Duration total, final java.util.function.Supplier<Object> stamp,
            final BuildWatch.Sleeper sleep) throws InterruptedException {
        final Object before = stamp.get();
        Duration left = total;
        while (!left.isZero() && !left.isNegative()) {
            final Duration step = left.compareTo(STEP) < 0 ? left : STEP;
            sleep.sleep(step);
            left = left.minus(step);
            if (!java.util.Objects.equals(before, stamp.get())) {
                return;
            }
        }
    }

    /** The vault file's last change, or "" when there is none. */
    private Object vaultStamp() {
        try {
            return Files.getLastModifiedTime(context.paths().vault().vaultFile());
        } catch (IOException ex) {
            return "";
        }
    }

    private void said(final TaskPaths paths, final String problem) {
        try {
            new TaskBuilds(paths).reader(container, forge, problem);
        } catch (IOException ex) {
            // Only the view is missing it; the log above has it.
        }
    }

    /**
     * Returns what a question to the forge carries, the credential read from the vault for this one question.
     *
     * @throws BuildWatch.NotNow When the vault is shut or does not hold the entry, which the task is told as it is.
     */
    private Target target() {
        final var entries = context.readableCredentials();
        if (entries.isEmpty()) {
            throw new BuildWatch.NotNow("the vault is shut, so Sokar cannot read the forge credential '" + credential
                    + "'; it asks the forge again once the vault is unlocked");
        }
        final var entry = entries.get().get(credential);
        if (entry == null) {
            throw new BuildWatch.NotNow("the vault holds no entry '" + credential + "', which the project names for"
                    + " builds; store it with 'sokar vault put " + credential + "'");
        }
        return new Target(upstream, api, entry.value());
    }
}
