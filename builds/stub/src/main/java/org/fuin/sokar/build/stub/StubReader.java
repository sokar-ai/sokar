package org.fuin.sokar.build.stub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.build.api.Build;
import org.fuin.sokar.build.api.BuildMain;
import org.fuin.sokar.build.api.BuildReader;
import org.fuin.sokar.build.api.BuildRefused;
import org.fuin.sokar.build.api.Target;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A build reader for the tests: it answers from a file instead of a forge, read again on every call, so a test moves a
 * build from running to failed by writing the file.
 * <p>
 * The file is {@code answers.json} beside the executable, or what {@code SOKAR_BUILD_STUB_ANSWERS} names:
 *
 * <pre>
 * {"token": "the one the vault holds",
 *  "heads": {"sokar/fix-login": "4f2a…"},
 *  "builds": {"4f2a…": {"verdict": "failure", "jobs": [{"name": "Build / test", "result": "failure", "log": "…"}],
 *                       "detail": ""}},
 *  "refuse": {"reason": "RATE_LIMITED", "detail": "…", "retryAfter": 30}}
 * </pre>
 *
 * {@code heads} maps a branch at the forge to its head commit: the branch the task's work reaches there, which for an
 * online task is {@code sokar/<task>}, not the task's name. A {@code token} there is the only one accepted; any other is refused as the forge would refuse it, so a test sees
 * that the token Sokar sends is the vault's. A {@code refuse} refuses every call. Its jobs are listed once the
 * verdict is failure or final, each with its log when it failed, or every one's when every log is asked for and the
 * verdict is final - as a forge's reader does.
 */
public final class StubReader implements BuildReader {

    /** The forge a project file names to read builds from this reader, chosen apart from the stub agent's name. */
    public static final String FORGE = "stub-forge";

    /** The environment variable that names the answers, for a test that cannot write beside the executable. */
    public static final String ANSWERS = "SOKAR_BUILD_STUB_ANSWERS";

    private final Path answers;

    /**
     * Constructor.
     *
     * @param answers The file to answer from.
     */
    public StubReader(final Path answers) {
        this.answers = answers;
    }

    /**
     * Runs the stub as a reader executable.
     *
     * @param arguments {@code describe}, or {@code serve <socket>}.
     */
    public static void main(final String[] arguments) {
        BuildMain.run(new StubReader(answers(System.getenv(ANSWERS),
                ProcessHandle.current().info().command().orElse(FORGE))), arguments);
    }

    /**
     * Returns where the answers are.
     *
     * @param named What {@link #ANSWERS} says, or {@code null}.
     * @param command The running executable.
     * @return The file.
     */
    static Path answers(final @Nullable String named, final String command) {
        if (named != null && !named.isBlank()) {
            return Path.of(named);
        }
        final Path parent = Path.of(command).toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve("answers.json");
    }

    @Override
    public String forge() {
        return FORGE;
    }

    @Override
    public String head(final Target target, final String branch) {
        final Map<?, ?> read = read(target);
        return read.get("heads") instanceof Map<?, ?> heads && heads.get(branch) != null
                ? String.valueOf(heads.get(branch)) : "";
    }

    @Override
    public Build look(final Target target, final String commit, final Build.Logs logs) {
        final Map<?, ?> read = read(target);
        if (!(read.get("builds") instanceof Map<?, ?> builds) || !(builds.get(commit) instanceof Map<?, ?> build)) {
            return Build.unknown("no build of " + commit);
        }
        final String verdict = text(build.get("verdict"));
        final boolean done = List.of(Build.SUCCESS, Build.FAILURE, Build.CANCELLED).contains(verdict);
        final List<Build.Job> jobs = new ArrayList<>();
        if (done && build.get("jobs") instanceof List<?> listed) {
            for (final Object each : listed) {
                if (each instanceof Map<?, ?> job) {
                    final String result = text(job.get("result"));
                    final Object log = "failure".equals(result) || logs == Build.Logs.ALL ? job.get("log") : null;
                    jobs.add(log == null ? new Build.Job(text(job.get("name")), result)
                            : new Build.Job(text(job.get("name")), result,
                                    String.valueOf(log).getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        return new Build(verdict, jobs, text(build.get("detail")));
    }

    private Map<?, ?> read(final Target target) {
        final Object parsed;
        try {
            parsed = Json.parse(Files.readString(answers, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BuildRefused(BuildRefused.Reason.UNREACHABLE, "the stub has no answers at " + answers);
        }
        if (!(parsed instanceof Map<?, ?> read)) {
            throw new BuildRefused(BuildRefused.Reason.UNREACHABLE, answers + " is not a JSON object");
        }
        if (read.get("refuse") instanceof Map<?, ?> refuse) {
            throw new BuildRefused(BuildRefused.Reason.valueOf(text(refuse.get("reason"))), text(refuse.get("detail")),
                    refuse.get("retryAfter") instanceof Number number ? number.longValue() : 0);
        }
        if (read.get("token") != null && !text(read.get("token")).equals(target.token())) {
            throw new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED, "the stub was given another token");
        }
        return read;
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
