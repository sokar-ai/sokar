package org.fuin.sokar.agent.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What waiting for a person looks like in this agent's own output - declared by the agent, because only
 * the agent knows its wording, and read by Sokar from output the host already has.
 * <p>
 * <strong>Literals, bounded when the manifest is read.</strong> A rule is a piece of text and a region,
 * never an expression: a pattern that arrives with a package is data from outside, and a backtracking
 * engine would let it cost the daemon its health. With literals the cost of a match is linear in the
 * screen, and the bounds below leave a declaration nothing to earn a timeout with - a declaration past
 * them is refused when the manifest is read, which is a refusal somebody can act on.
 * <p>
 * <strong>Nothing here is a guess.</strong> Only a declared match says "waiting". A screen that matched
 * nothing says "not waiting", and an agent that declares nothing is not asked at all - the caller says it
 * cannot tell, which is a different answer from "not waiting".
 *
 * @param screen What the attached agent's screen shows while it waits for the person; any one matching
 *        means waiting. Empty when the agent declares nothing for its screen.
 * @param notItsScreen What shows that the screen is not the agent's own - a pager or a transcript viewer
 *        open over it. Any one matching means the screen says nothing about the agent.
 * @param lastMessage Where a finished unattended run's last message is in its machine-readable output, or
 *        {@code null} when the agent does not say.
 */
public record Waiting(List<Rule> screen, List<Rule> notItsScreen, @Nullable LastMessage lastMessage) {

    /** The most rules in one list. */
    public static final int MOST_RULES = 16;

    /** The longest text a rule may match. */
    public static final int LONGEST_TEXT = 200;

    /** The most lines a region may take from the bottom of the screen. */
    public static final int MOST_LINES = 50;

    /**
     * Constructor with validation.
     *
     * @param screen The screen rules.
     * @param notItsScreen The rules that say the screen is not the agent's.
     * @param lastMessage Where the last message is, or {@code null}.
     */
    public Waiting {
        screen = List.copyOf(screen);
        notItsScreen = List.copyOf(notItsScreen);
        for (final List<Rule> rules : List.of(screen, notItsScreen)) {
            if (rules.size() > MOST_RULES) {
                throw new AgentException("A waiting declaration takes at most " + MOST_RULES + " rules in a list, not "
                        + rules.size());
            }
        }
        if (screen.isEmpty() && notItsScreen.isEmpty() && lastMessage == null) {
            throw new AgentException("A waiting declaration that declares nothing is written by leaving it out");
        }
        if (screen.isEmpty() && !notItsScreen.isEmpty()) {
            throw new AgentException("'not_its_screen' only means something beside 'screen' rules");
        }
    }

    /**
     * One piece of text in one region of the screen.
     *
     * @param contains The text, one line, matched without regard to case.
     * @param lastLines How many of the last non-empty lines it is looked for in, or {@code null} for the whole
     *        screen. Measured on the screen as it is drawn, which follows whoever is attached.
     * @param waitingFor What the agent is waiting for, as a person reads it, or {@code null}.
     */
    public record Rule(String contains, @Nullable Integer lastLines, @Nullable String waitingFor) {

        /**
         * Constructor with validation.
         *
         * @param contains The text.
         * @param lastLines The region, or {@code null}.
         * @param waitingFor What it waits for, or {@code null}.
         */
        public Rule {
            if (contains.isBlank() || contains.length() > LONGEST_TEXT || contains.contains("\n")
                    || contains.contains("\r")) {
                throw new AgentException("A waiting rule matches one line of 1 to " + LONGEST_TEXT
                        + " characters, not '" + contains + "'");
            }
            if (lastLines != null && (lastLines < 1 || lastLines > MOST_LINES)) {
                throw new AgentException("A waiting rule looks in the last 1 to " + MOST_LINES + " lines, not "
                        + lastLines);
            }
            if (waitingFor != null && (waitingFor.isBlank() || waitingFor.length() > LONGEST_TEXT)) {
                throw new AgentException("What a rule says the agent waits for is 1 to " + LONGEST_TEXT
                        + " characters");
            }
        }

        /**
         * Whether this rule matches a screen.
         *
         * @param lines The screen's non-empty lines, top to bottom.
         * @return true when the text is in the rule's region
         */
        boolean matches(List<String> lines) {
            final int from = lastLines == null ? 0 : Math.max(0, lines.size() - lastLines);
            final String wanted = contains.toLowerCase(Locale.ROOT);
            for (int i = from; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase(Locale.ROOT).contains(wanted)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Where the last message of a finished unattended run is: the last record whose fields equal the given
     * values, and the field of it that holds the text.
     *
     * @param record Top-level fields and the values they must have, at least one.
     * @param text The top-level field holding the message.
     */
    public record LastMessage(Map<String, String> record, String text) {

        /**
         * Constructor with validation.
         *
         * @param record The fields to match.
         * @param text The field holding the text.
         */
        public LastMessage {
            record = Map.copyOf(record);
            if (record.isEmpty() || record.size() > MOST_RULES) {
                throw new AgentException("A last message is found by 1 to " + MOST_RULES + " fields, not "
                        + record.size());
            }
            if (text.isBlank()) {
                throw new AgentException("A last message names the field that holds its text");
            }
        }

        /**
         * Picks the last message out of a run's records.
         *
         * @param records The run's machine-readable output, one parsed record per line, in order.
         * @return The text of the last record that matches, or {@code null} when none does.
         */
        public @Nullable String of(List<?> records) {
            String last = null;
            for (final Object each : records) {
                if (each instanceof Map<?, ?> fields && matches(fields) && fields.get(text) instanceof String said) {
                    last = said;
                }
            }
            return last;
        }

        private boolean matches(Map<?, ?> fields) {
            for (final Map.Entry<String, String> wanted : record.entrySet()) {
                if (!wanted.getValue().equals(fields.get(wanted.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** What one reading of the screen says. */
    public enum Seen {

        /** A declared rule matched: the agent is waiting for the person. */
        WAITING,

        /** Nothing declared matched. */
        NOT_WAITING,

        /** The screen shows something that is not the agent's own: it says nothing about the agent. */
        NOT_ITS_SCREEN
    }

    /**
     * What a screen says, and what the agent waits for when it waits.
     *
     * @param seen What it says.
     * @param waitingFor What the matching rule says the agent waits for, or {@code null}.
     */
    public record Reading(Seen seen, @Nullable String waitingFor) {
    }

    /**
     * Reads a screen as tmux draws it.
     *
     * @param drawn The screen's text, lines separated by newlines, as drawn - not the byte stream.
     * @return What it says; {@code NOT_ITS_SCREEN} before anything else, so a pager open over a question is
     *         not read as the question.
     */
    public Reading read(String drawn) {
        final List<String> lines = new ArrayList<>();
        for (final String line : drawn.split("\n", -1)) {
            final String text = line.stripTrailing();
            if (!text.isEmpty()) {
                lines.add(text);
            }
        }
        for (final Rule rule : notItsScreen) {
            if (rule.matches(lines)) {
                return new Reading(Seen.NOT_ITS_SCREEN, null);
            }
        }
        for (final Rule rule : screen) {
            if (rule.matches(lines)) {
                return new Reading(Seen.WAITING, rule.waitingFor());
            }
        }
        return new Reading(Seen.NOT_WAITING, null);
    }

}
