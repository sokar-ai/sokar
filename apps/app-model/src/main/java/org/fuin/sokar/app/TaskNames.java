package org.fuin.sokar.app;

/**
 * Names a task nobody named: after its repository.
 * <p>
 * An unnamed start used to be called {@code shell} whatever it was, so an agent's session read as the wrong mode
 * (2026-10-01). The repository is what a person recognises a task by. A second unnamed task on the
 * same repository gets the next free name - {@code my-first-2} - and never takes over the first silently.
 */
public final class TaskNames {

    private TaskNames() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the name for an unnamed task.
     *
     * @param context The machine, whose tasks a new name must not clash with.
     * @param project The project's name.
     * @param repository The repository's name.
     * @return The name, free on this machine.
     */
    public static String fromRepository(final SokarContext context, final String project, final String repository) {
        // A leading '.' or '_' (GitHub's '.github') is neither a container name nor a peer name.
        String base = repository.replaceFirst("^[._]+", "");
        if (base.isEmpty()) {
            base = "work";
        }
        final java.util.Set<String> taken = new java.util.HashSet<>();
        new TaskInventory(context).tasks().forEach(task -> taken.add(task.name()));
        final String prefix = "sokar-" + project + "-";
        String chosen = base;
        for (int at = 2; taken.contains(prefix + chosen); at++) {
            chosen = base + "-" + at;
        }
        return chosen;
    }
}
