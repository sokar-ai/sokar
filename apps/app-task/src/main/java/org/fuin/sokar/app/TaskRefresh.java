package org.fuin.sokar.app;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.Repository;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.runtime.ContainerSummary;

/**
 * Brings a task's repository at the gate up to its source - the checkout it was started in, or its remote - and tells
 * its agent what moved.
 * <p>
 * Otherwise only a new task's start brought the source in: a running task, or one started again, kept what the gate
 * held when it was first started, and nothing told its agent there was more.
 */
public final class TaskRefresh {

    /** What a refresh did. */
    public enum Outcome {

        /** At least one branch moved; the agent was told, if it has a mailbox. */
        MOVED,

        /** The gate already held what the source holds. */
        UNCHANGED,

        /** The task has no gate to refresh: an online task fetches its upstream itself. */
        NOT_GATED,

        /** No task of that name. */
        NO_SUCH_TASK,

        /** The source could not be read. */
        FAILED
    }

    /**
     * What a refresh did.
     *
     * @param outcome What happened.
     * @param moved The branches that moved, and the commit each names now.
     * @param detail Why it failed, or what to know; "" otherwise.
     * @param told Whether the agent was told.
     */
    public record Result(Outcome outcome, Map<String, String> moved, String detail, boolean told) {

        /**
         * Returns this as plain values, for a wire.
         *
         * @return The result.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("outcome", outcome.name());
            map.put("moved", moved);
            map.put("detail", detail);
            map.put("told", told);
            return map;
        }
    }

    private final SokarContext context;

    /**
     * Returns what a refresh that moved something says, in one line.
     *
     * @param result What the refresh did.
     * @return For example {@code moved main -> 1a2b3c4d5e6f; its agent was told}.
     */
    public static String said(final Result result) {
        return "moved " + result.moved().entrySet().stream().map(each -> each.getKey() + " -> "
                + each.getValue().substring(0, Math.min(12, each.getValue().length())))
                .collect(java.util.stream.Collectors.joining(", "))
                + (result.told() ? "; its agent was told, and 'git fetch sokar' brings it" : "; its agent was not told: "
                        + (result.detail().isEmpty() ? "it has no mailbox" : result.detail()));
    }

    /**
     * Constructor.
     *
     * @param context This machine.
     */
    public TaskRefresh(final SokarContext context) {
        this.context = context;
    }

    /**
     * Refreshes one task's repository at the gate.
     *
     * @param container The task's container.
     * @return What happened.
     */
    public Result refresh(final String container) {
        final List<ContainerSummary> tasks = context.podman().sokarTasks();
        // By its container's name, or by the task's own as 'sokar approve' takes it, when only one task has it.
        final List<ContainerSummary> named = tasks.stream().filter(each -> each.name().equals(container)).toList();
        final List<ContainerSummary> found = !named.isEmpty() ? named : tasks.stream()
                .filter(each -> each.project() != null && each.name().equals("sokar-" + each.project() + "-" + container))
                .toList();
        final ContainerSummary summary = found.size() == 1 ? found.getFirst() : null;
        if (summary == null) {
            return new Result(Outcome.NO_SUCH_TASK, Map.of(), "no task '" + container + "' on this machine", false);
        }
        if (summary.project() == null) {
            return new Result(Outcome.NOT_GATED, Map.of(), container + " names no project", false);
        }
        final GitGate gate;
        try {
            final Project project = GateSupport.byName(context, summary.project());
            if (project.securityClass() == SecurityClass.ONLINE) {
                return new Result(Outcome.NOT_GATED, Map.of(), "an online task has no gate; its agent fetches its"
                        + " upstream itself", false);
            }
            final Repository repository = summary.repository() == null || summary.repository().isBlank()
                    ? project.ownRepository() : project.repository(summary.repository());
            if (repository == null) {
                return new Result(Outcome.NOT_GATED, Map.of(), "'" + summary.project() + "' no longer names the"
                        + " repository '" + summary.repository() + "'", false);
            }
            gate = GateSupport.gate(context, project, repository, null, null);
        } catch (ProjectException ex) {
            return new Result(Outcome.FAILED, Map.of(), String.valueOf(ex.getMessage()), false);
        }
        if (!Files.isDirectory(gate.mirror())) {
            return new Result(Outcome.NOT_GATED, Map.of(), "no gate has been made for this task's repository", false);
        }
        final Map<String, String> before = gate.branches();
        final String failed = gate.refresh();
        if (!failed.isEmpty()) {
            return new Result(Outcome.FAILED, Map.of(), failed, false);
        }
        final Map<String, String> moved = new LinkedHashMap<>();
        gate.branches().forEach((branch, commit) -> {
            if (!commit.equals(before.get(branch))) {
                moved.put(branch, commit);
            }
        });
        if (moved.isEmpty()) {
            return new Result(Outcome.UNCHANGED, Map.of(), "", false);
        }
        boolean told = false;
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(summary.name()));
        if (mailbox.exists()) {
            try {
                new PersonNote().moved(mailbox, moved);
                new AgentWake(context).announce(mailbox, summary.name(), null);
                told = true;
            } catch (java.io.IOException ex) {
                return new Result(Outcome.MOVED, Map.copyOf(moved), "its agent could not be told: " + ex.getMessage(),
                        false);
            }
        }
        return new Result(Outcome.MOVED, Map.copyOf(moved), "", told);
    }
}
