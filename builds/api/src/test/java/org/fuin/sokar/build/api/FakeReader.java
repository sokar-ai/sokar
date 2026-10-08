package org.fuin.sokar.build.api;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A reader the tests start as an executable: fixed answers, so a test sees what crossed the socket.
 * <p>
 * A branch {@code main} points at forty {@code a}s; a commit starting with {@code f} failed with a log holding a NUL
 * byte, one starting with {@code r} is rate limited for 30 seconds, any other succeeded, with its one job's log only
 * when every log is asked for; the token {@code wrong} is refused.
 */
public final class FakeReader implements BuildReader {

    /** What a failed build's log holds: not text, so the protocol must carry bytes. */
    static final byte[] LOG = "step 3 failed\u0000\nexit 1\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Runs the fake as a reader executable.
     *
     * @param arguments As every reader takes them.
     */
    public static void main(final String[] arguments) {
        BuildMain.run(new FakeReader(), arguments);
    }

    @Override
    public String forge() {
        return "fake";
    }

    @Override
    public String head(final Target target, final String branch) {
        refuseWrongToken(target);
        return "main".equals(branch) ? "a".repeat(40) : "";
    }

    @Override
    public Build look(final Target target, final String commit, final Build.Logs logs) {
        refuseWrongToken(target);
        if (commit.startsWith("r")) {
            throw new BuildRefused(BuildRefused.Reason.RATE_LIMITED, "the forge's rate limit is reached", 30);
        }
        if (commit.startsWith("f")) {
            return new Build(Build.FAILURE, List.of(new Build.Job("Build / unit tests", "failure", LOG)), "");
        }
        return logs == Build.Logs.ALL
                ? new Build(Build.SUCCESS, List.of(new Build.Job("Build / unit tests", "success",
                        "all green\n".getBytes(StandardCharsets.UTF_8))), "")
                : new Build(Build.SUCCESS, "");
    }

    private static void refuseWrongToken(final Target target) {
        if ("wrong".equals(target.token())) {
            throw new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED, "bad credentials");
        }
    }
}
