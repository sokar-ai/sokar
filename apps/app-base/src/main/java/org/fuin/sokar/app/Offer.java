package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Where something is missing and Sokar would tell a person which command to type, offers that command's own remedy
 * instead, checks again after it, and carries on.
 * <p>
 * One helper for every such place, so each asks alike: at a terminal it asks and runs the remedy; without one, or with
 * {@code --no-input}, it refuses as before and names the command. {@code --yes} takes a harmless remedy without asking
 * and never a destructive one, which first shows what it touches and defaults to no. A remedy is the function of the
 * command a person would have typed, never a copy of it, and is never taken on trust: the precondition is checked
 * again after it. Several gaps at one start are asked one after another by calling this for each in turn.
 */
public final class Offer {

    /** Asks a person, or answers {@code null} when nobody is at a terminal to answer. */
    @FunctionalInterface
    public interface Asker {

        /** Nobody to ask: a daemon, a script, a pipe. */
        Asker NOBODY = question -> null;

        /**
         * Asks once.
         *
         * @param question What is asked, with its choices.
         * @return What was typed, or {@code null} when nobody can be asked.
         */
        @Nullable String ask(String question);

        /**
         * Returns the asker of this process's terminal.
         *
         * @return One that asks only when standard input and output are a terminal.
         */
        static Asker terminal() {
            return question -> {
                final java.io.Console console = System.console();
                // A console object alone is no terminal: since Java 22 one exists for a pipe too.
                return console == null || !console.isTerminal() ? null : console.readLine("%s ", question);
            };
        }
    }

    /**
     * One gap and its remedy.
     *
     * @param missing What is missing, as the refusal says it.
     * @param question What is asked, without its choices.
     * @param command The command a person would type, named where nobody is asked.
     * @param affected What a destructive remedy touches, shown before asking; empty for a harmless one.
     * @param destructive Whether the remedy discards, deletes or leaves something behind: asked with no as default, and
     *        never taken by {@code --yes}.
     * @param remedy The command's own function; {@code false} when it failed.
     * @param holds Whether the precondition holds, checked before and again after the remedy.
     */
    public record Remedy(String missing, String question, String command, String affected, boolean destructive,
            BooleanSupplier remedy, BooleanSupplier holds) {
    }

    /** Nobody asked and nothing taken: every gap refused as before. */
    public static final Offer NOBODY = new Offer(Asker.NOBODY, true, false, null);

    private final Asker asker;

    private final boolean noInput;

    private final boolean yes;

    private final @Nullable PrintWriter err;

    /**
     * Constructor.
     *
     * @param asker Who is asked.
     * @param noInput Whether nothing is asked, as {@code --no-input} says.
     * @param yes Whether a harmless remedy is taken without asking, as {@code --yes} says.
     * @param err Where a refusal is said, or {@code null} to say it at {@link #resolve(Remedy, PrintWriter)}.
     */
    public Offer(final Asker asker, final boolean noInput, final boolean yes, final @Nullable PrintWriter err) {
        this.asker = asker;
        this.noInput = noInput;
        this.yes = yes;
        this.err = err;
    }

    /**
     * Makes a gap good, or refuses as before.
     *
     * @param gap The gap and its remedy.
     * @return Whether the precondition holds now.
     */
    public boolean resolve(final Remedy gap) {
        return resolve(gap, java.util.Objects.requireNonNull(err, "no writer to say a refusal on"));
    }

    /**
     * Makes a gap good, or refuses as before, saying it where the caller says.
     *
     * @param gap The gap and its remedy.
     * @param said Where a refusal or what a destructive remedy touches is said.
     * @return Whether the precondition holds now.
     */
    public boolean resolve(final Remedy gap, final PrintWriter said) {
        if (gap.holds().getAsBoolean()) {
            return true;
        }
        if (!wanted(gap, said)) {
            said.println("sokar: " + gap.missing() + "; run '" + gap.command() + "'");
            said.flush();
            return false;
        }
        if (gap.remedy().getAsBoolean() && gap.holds().getAsBoolean()) {
            return true;
        }
        said.println("sokar: " + gap.missing() + " still; run '" + gap.command() + "'");
        said.flush();
        return false;
    }

    private boolean wanted(final Remedy gap, final PrintWriter said) {
        if (noInput) {
            return false;
        }
        if (yes && !gap.destructive()) {
            return true;
        }
        if (gap.destructive() && !gap.affected().isBlank()) {
            said.println("sokar: " + gap.missing() + " - this touches " + gap.affected());
            said.flush();
        }
        final String answer = asker.ask(gap.missing() + ". " + gap.question() + (gap.destructive() ? " [y/N]" : " [Y/n]"));
        if (answer == null) {
            return false;
        }
        final String given = answer.strip().toLowerCase(java.util.Locale.ROOT);
        return gap.destructive() ? given.equals("y") || given.equals("yes")
                : given.isEmpty() || given.equals("y") || given.equals("yes");
    }

    /**
     * Asks a person to choose one of several where a refusal would ask for an option, or refuses as before.
     * <p>
     * A choice is never taken by {@code --yes}: there is no harmless default among several.
     *
     * @param missing What is missing, as the refusal says it.
     * @param options What may be chosen, in the order shown.
     * @param command The command a person would type, named where nobody is asked.
     * @return The one chosen, or {@code null} when nothing was, said with the command.
     */
    public @Nullable String choose(final String missing, final java.util.List<String> options, final String command) {
        final PrintWriter said = java.util.Objects.requireNonNull(err, "no writer to say a refusal on");
        String chosen = null;
        if (!noInput && !options.isEmpty()) {
            said.println("sokar: " + missing + ":");
            for (int at = 0; at < options.size(); at++) {
                said.println("    " + (at + 1) + "  " + options.get(at));
            }
            said.flush();
            final String answer = asker.ask("Which one? [1-" + options.size() + "]");
            if (answer != null && answer.strip().matches("[0-9]+")) {
                final int number = Integer.parseInt(answer.strip());
                chosen = number >= 1 && number <= options.size() ? options.get(number - 1) : null;
            }
        }
        if (chosen == null) {
            said.println("sokar: " + missing + "; run '" + command + "'");
            said.flush();
        }
        return chosen;
    }
}
