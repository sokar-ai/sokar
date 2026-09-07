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
        return "The agent tried to reach " + destination + " over " + protocol + ".";
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
        return destination + " over " + protocol + " stays blocked."
                + " It will not be asked again for this task.";
    }
}
