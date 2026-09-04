package org.fuin.sokar.agent.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How an agent's command line is assembled for a non-interactive run.
 * <p>
 * Every field is the agent's own spelling of a common idea. Keeping them as data is what allows
 * one command builder to serve every agent: the alternative, seen in the reference implementation,
 * is a {@code build_headless_command} that dispatches on the agent's name and quietly gives a new
 * agent the wrong shape.
 *
 * @param promptFlag Flag introducing the prompt, for example {@code -p}. Empty when the agent
 *        takes the prompt positionally, which is a real shape rather than a missing value: an
 *        agent invoked as {@code tool "do the thing"} has no flag to name.
 * @param modelFlag Flag selecting a model, or {@code null} if the agent has none.
 * @param maxTurnsFlag Flag limiting turns, or {@code null}.
 * @param verboseFlag Flag enabling verbose output, or {@code null}.
 * @param outputFormatFlags Flags selecting a machine-readable output format, possibly empty.
 */
public record HeadlessFlags(String promptFlag, @Nullable String modelFlag,
        @Nullable String maxTurnsFlag, @Nullable String verboseFlag,
        List<String> outputFormatFlags) {

    /**
     * Constructor with all data.
     *
     * @param promptFlag Flag introducing the prompt.
     * @param modelFlag Flag selecting a model, or {@code null}.
     * @param maxTurnsFlag Flag limiting turns, or {@code null}.
     * @param verboseFlag Flag enabling verbose output, or {@code null}.
     * @param outputFormatFlags Flags selecting a machine-readable output format.
     */
    public HeadlessFlags {
        outputFormatFlags = List.copyOf(outputFormatFlags);
    }
}
