package org.fuin.sokar.build.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the forge said about the build of one commit.
 *
 * @param verdict One of {@link #VERDICTS}.
 * @param jobs The jobs whose log is handed over, in the forge's order: each failed one, or with {@link Logs#ALL} every
 *         one once the verdict is final. Empty while the build is queued or running.
 * @param detail Why the verdict is {@link #UNKNOWN}, or what more the forge said; "" otherwise.
 */
public record Build(String verdict, List<Job> jobs, String detail) {

    /** Not started yet. */
    public static final String QUEUED = "queued";

    /** Started, not finished. */
    public static final String RUNNING = "running";

    /** Finished, and every run of the commit succeeded. */
    public static final String SUCCESS = "success";

    /** Finished, and a run failed. */
    public static final String FAILURE = "failure";

    /** Finished because somebody cancelled it. */
    public static final String CANCELLED = "cancelled";

    /** The forge holds no build of the commit, or none Sokar can read a verdict from; 'detail' says which. */
    public static final String UNKNOWN = "unknown";

    /** Every verdict, in the order a build passes through them. */
    public static final List<String> VERDICTS = List.of(QUEUED, RUNNING, SUCCESS, FAILURE, CANCELLED, UNKNOWN);

    /** The most of a log's end a reader hands over, per job: enough for a stack trace and the step that failed. */
    public static final int TAIL = 64 * 1024;

    /** The most jobs one build hands logs over for, so a matrix of hundreds cannot flood a task. */
    public static final int MOST_JOBS = 50;

    /**
     * Which logs a project wants handed to its tasks.
     */
    public enum Logs {

        /** The log of each failed job. */
        FAILURE,

        /** The log of every job, once the verdict will not change any more. */
        ALL;

        /**
         * Returns the policy a project file names.
         *
         * @param name "failure" or "all".
         * @return It.
         * @throws IllegalArgumentException For anything else.
         */
        public static Logs of(final String name) {
            return switch (name) {
                case "failure" -> FAILURE;
                case "all" -> ALL;
                default -> throw new IllegalArgumentException("'" + name + "' is not 'failure' or 'all'");
            };
        }

        /**
         * Returns its name as a project file and the protocol write it.
         *
         * @return "failure" or "all".
         */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One job of the build and the end of its log.
     *
     * @param name As the forge spells it, workflow and job: {@code Build / unit tests}.
     * @param result What became of it, as the forge says it, lower case: success, failure, cancelled, skipped, ...
     * @param log Its log's last {@link #TAIL} bytes; {@code null} when the forge gave none.
     */
    public record Job(String name, String result, byte @Nullable [] log) {

        /**
         * Constructor keeping only a log's end.
         *
         * @param name Its name.
         * @param result What became of it.
         * @param log Its log, or {@code null}.
         */
        public Job {
            if (log != null) {
                log = log.length > TAIL ? java.util.Arrays.copyOfRange(log, log.length - TAIL, log.length)
                        : log.clone();
            }
        }

        /**
         * Constructor for a job without a log.
         *
         * @param name Its name.
         * @param result What became of it.
         */
        public Job(final String name, final String result) {
            this(name, result, null);
        }

        @Override
        public byte @Nullable [] log() {
            return log == null ? null : log.clone();
        }

        /**
         * Returns the log's end as text, for a person.
         *
         * @return It, invalid UTF-8 replaced; "" when there is none.
         */
        public String logText() {
            return log == null ? "" : new String(log, StandardCharsets.UTF_8);
        }

        @Override
        public boolean equals(final @Nullable Object other) {
            return other instanceof Job that && name.equals(that.name) && result.equals(that.result)
                    && java.util.Arrays.equals(log, that.log);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, result, java.util.Arrays.hashCode(log));
        }

        @Override
        public String toString() {
            return "Job[name=" + name + ", result=" + result + ", log="
                    + (log == null ? "none" : log.length + " bytes") + "]";
        }
    }

    /**
     * Constructor with checks.
     *
     * @param verdict The verdict.
     * @param jobs The jobs with their logs.
     * @param detail More.
     */
    public Build {
        if (!VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException("Not a verdict: '" + verdict + "', expected one of " + VERDICTS);
        }
        jobs = List.copyOf(jobs.size() > MOST_JOBS ? jobs.subList(0, MOST_JOBS) : jobs);
    }

    /**
     * Constructor for a build that hands no log over.
     *
     * @param verdict The verdict.
     * @param detail More, or "".
     */
    public Build(final String verdict, final String detail) {
        this(verdict, List.of(), detail);
    }

    /**
     * Returns a build without a verdict.
     *
     * @param detail Why.
     * @return It.
     */
    public static Build unknown(final String detail) {
        return new Build(UNKNOWN, detail);
    }

    /**
     * Returns whether the build will not change any more.
     *
     * @return {@code true} for success, failure and cancelled.
     */
    public boolean finished() {
        return SUCCESS.equals(verdict) || FAILURE.equals(verdict) || CANCELLED.equals(verdict);
    }

    /**
     * Returns the build as the protocol carries it, each log in base64 since it need not be text.
     *
     * @return The fields.
     */
    Map<String, Object> asMap() {
        final List<Map<String, Object>> carried = new ArrayList<>();
        for (final Job job : jobs) {
            final Map<String, Object> each = new LinkedHashMap<>();
            each.put("name", job.name());
            each.put("result", job.result());
            final byte[] log = job.log();
            if (log != null) {
                each.put("log", Base64.getEncoder().encodeToString(log));
            }
            carried.add(each);
        }
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("verdict", verdict);
        map.put("jobs", carried);
        map.put("detail", detail);
        return map;
    }

    /**
     * Reads a build as the protocol carries it.
     *
     * @param map The fields.
     * @return The build.
     * @throws IllegalArgumentException When a field is not what the protocol says.
     */
    static Build of(final Map<String, Object> map) {
        final List<Job> jobs = new ArrayList<>();
        if (map.get("jobs") instanceof List<?> carried) {
            for (final Object each : carried) {
                if (each instanceof Map<?, ?> job) {
                    jobs.add(job.get("log") instanceof String log
                            ? new Job(text(job.get("name")), text(job.get("result")), Base64.getDecoder().decode(log))
                            : new Job(text(job.get("name")), text(job.get("result"))));
                }
            }
        }
        return new Build(text(map.get("verdict")), jobs, text(map.get("detail")));
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
