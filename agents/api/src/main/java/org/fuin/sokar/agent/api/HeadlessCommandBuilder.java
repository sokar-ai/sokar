package org.fuin.sokar.agent.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles one agent's command line from its declared flags.
 * <p>
 * <strong>One builder for every agent.</strong> The reference implementation has a
 * {@code build_headless_command} that reads {@code if self.name == "claude"} and dispatches to a
 * Claude-specific assembler; every agent added after that either matches the generic shape or is
 * silently wrong. Here the differences are flags, and an agent whose shape genuinely differs
 * overrides {@link Agent#headlessCommand(RunRequest)} in its own module.
 */
final class HeadlessCommandBuilder {

    private HeadlessCommandBuilder() {
        throw new UnsupportedOperationException("Utility class");
    }

    static List<String> build(AgentDefinition definition, RunRequest request) {

        final HeadlessFlags flags = definition.headless();
        // Starts from the sandboxed command rather than the bare binary, so the flags that
        // turn the agent's own permission prompts off are there unconditionally and in the same
        // place as on the attached path. There is no option for this: the box is why the agent
        // is in here, and in an unattended run there is nobody to answer a prompt anyway.
        final List<String> command = new ArrayList<>(definition.sandboxedCommand());

        if (request.resumeSession() != null) {
            if (!definition.supportsResume()) {
                throw new AgentException("Agent '" + definition.name()
                        + "' cannot resume a session");
            }
            command.add(definition.resumeFlag());
            command.add(request.resumeSession());
        }
        if (request.model() != null) {
            if (flags.modelFlag() == null) {
                throw new AgentException("Agent '" + definition.name()
                        + "' does not take a model");
            }
            command.add(flags.modelFlag());
            command.add(request.model());
        }
        if (request.maxTurns() != null) {
            if (flags.maxTurnsFlag() == null) {
                throw new AgentException("Agent '" + definition.name()
                        + "' does not take a turn limit");
            }
            command.add(flags.maxTurnsFlag());
            command.add(String.valueOf(request.maxTurns()));
        }
        if (request.verbose() && flags.verboseFlag() != null) {
            command.add(flags.verboseFlag());
        }
        if (request.machineReadable()) {
            command.addAll(flags.outputFormatFlags());
        }

        // The prompt goes last: an agent that takes it positionally needs it after the flags, and
        // one that takes it behind a flag does not care. An empty flag is the positional case, and
        // adding it anyway would pass an empty argument the agent has to interpret.
        if (!flags.promptFlag().isBlank()) {
            command.add(flags.promptFlag());
        }
        command.add(request.prompt());

        return List.copyOf(command);
    }
}
