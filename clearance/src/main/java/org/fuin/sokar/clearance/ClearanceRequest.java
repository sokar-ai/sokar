package org.fuin.sokar.clearance;

/**
 * A question put to the operator.
 *
 * @param project Project the container belongs to.
 * @param task Task within that project, so an operator with several open knows which one asked.
 * @param destination Destination as it should be shown, for example {@code 1.1.1.1:443}.
 * @param address Address to add to the allow set if the answer is yes.
 * @param protocol Protocol name, for example {@code tcp}.
 */
public record ClearanceRequest(String project, String task, String destination, String address,
        String protocol) {

    /**
     * Returns the one-line summary shown in a notification.
     *
     * @return Summary.
     */
    public String summary() {
        return "Sokar: " + project + "/" + task + " blocked";
    }

    /**
     * Returns the body shown in a notification.
     *
     * @return Body.
     */
    public String body() {
        // Both readings, once, where the operator answers: a reach nobody declared is either an
        // ordinary missing declaration or an agent following somebody else's instructions, and nothing
        // here can tell which. Saying only the first would teach "allow" as the reflex.
        return "The agent tried to reach " + shown() + " over " + protocol + "."
                + " Either the project does not declare a host this work needs, or the agent is acting on"
                + " instructions from something it read. Allow it only if you expected this host.";
    }

    /**
     * Returns the summary shown when nobody answered in time.
     *
     * @return Summary.
     */
    public String expiredSummary() {
        return "Sokar: " + project + "/" + task + " not answered";
    }

    /**
     * Returns the body shown when nobody answered in time.
     * <p>
     * It says what the silence did, because it did something: the destination stays blocked and is
     * not asked about again, so an operator who comes back to the machine and finds nothing on
     * screen would otherwise have no way to tell a question that was never raised from one that
     * ran out.
     *
     * @return Body.
     */
    public String expiredBody() {
        return shown() + " over " + protocol + " stays blocked."
                + " It will not be asked again for this task.";
    }

    /** The most of a destination shown: a host name is at most 253 characters, and the warning must stay in view. */
    private static final int SHOWN_LIMIT = 300;

    /**
     * Returns the destination as a person may safely read it where they decide.
     * <p>
     * The name in it is the one the agent queried: markup a notification server reads, a right-to-left override that
     * makes it read as another host, or a name long enough to push the warning out of view went in as it came. Markup
     * is escaped, every control and format character written out, and the length bounded.
     *
     * @return The destination, safe to show.
     */
    public String shown() {
        final StringBuilder out = new StringBuilder();
        destination.codePoints().forEach(point -> {
            final int type = Character.getType(point);
            if (point == '<') {
                out.append("&lt;");
            } else if (point == '>') {
                out.append("&gt;");
            } else if (point == '&') {
                out.append("&amp;");
            } else if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                out.append(String.format("\\u%04x", point));
            } else {
                out.appendCodePoint(point);
            }
        });
        // Cut in the middle: the end holds the port and the address, which is what an answer opens.
        return out.length() > SHOWN_LIMIT
                ? out.substring(0, SHOWN_LIMIT - 60) + " … " + out.substring(out.length() - 60) : out.toString();
    }
}
