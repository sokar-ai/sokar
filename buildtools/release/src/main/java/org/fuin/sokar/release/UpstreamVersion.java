package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Says whether upstream has moved on from the version the module pins.
 * <p>
 * Every agent answers in the same lines, so the job that calls it does not know which source was read.
 * {@code update=yes} is not a failing exit: a scheduled job that goes red whenever there is work
 * teaches whoever watches it to ignore red.
 */
public final class UpstreamVersion {

    /** The question was answered, whatever the answer. */
    public static final int ANSWERED = 0;

    private final PrintStream out;

    private final PrintStream err;

    private final Web web;

    private final Map<String, String> env;

    /**
     * A lookup that reports on two streams.
     *
     * @param out where the answer goes
     * @param err where an unanswered question is explained
     * @param web where upstream is read
     * @param env the environment: {@code GITHUB_OUTPUT} and {@code GITHUB_TOKEN}
     */
    public UpstreamVersion(PrintStream out, PrintStream err, Web web, Map<String, String> env) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.web = Objects.requireNonNull(web, "web");
        this.env = Map.copyOf(env);
    }

    /**
     * Answers.
     *
     * @param pom the module's pom
     * @param channel the channel or dist-tag named on the command line, or null for the pom's
     * @param named a version a person named, answered as if upstream offered it, or null
     * @return {@link #ANSWERED} or {@link Stop#UNANSWERED}
     */
    public int answer(Path pom, @Nullable String channel, @Nullable String named) {
        final Map<String, String> answer = new LinkedHashMap<>();
        try {
            final Release release = Release.of(Pom.read(pom));
            final String have = release.pom().pinned();
            final String followed = channel != null ? channel : release.channel();
            if (named != null && !Versions.isVersion(named)) {
                throw Stop.unanswered("'" + named + "' is not a version");
            }
            final String there = named != null ? named : release.upstream().version(web, followed, env);
            answer.put("pinned", have);
            answer.put("upstream", there);
            answer.put("source", named != null ? "a version named by hand" : release.upstream().source(followed));
            answer.put("update", Versions.verdict(have, there, named != null).word());
            answer.put("major", Versions.majorMoved(have, there) ? "moved" : "same");
        } catch (Stop stop) {
            // Every stop is "could not tell" here: a pom that pins nothing is not "up to date".
            err.println("could not tell whether there is a new version - " + stop.getMessage());
            return Stop.UNANSWERED;
        }
        answer.forEach((key, value) -> out.println(key + "=" + value));
        final String output = env.get("GITHUB_OUTPUT");
        if (output != null && !output.isBlank()) {
            final StringBuilder lines = new StringBuilder();
            answer.forEach((key, value) -> lines.append(key).append('=').append(value).append('\n'));
            try {
                Files.writeString(Path.of(output), lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                err.println("could not append to GITHUB_OUTPUT " + output + ": " + ex.getMessage());
                return Stop.UNANSWERED;
            }
        }
        return ANSWERED;
    }

}
