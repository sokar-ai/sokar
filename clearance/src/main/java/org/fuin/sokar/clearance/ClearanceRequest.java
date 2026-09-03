package org.fuin.sokar.clearance;

/**
 * A question put to the operator.
 *
 * @param project Project the container belongs to.
 * @param destination Destination as it should be shown, for example {@code 1.1.1.1:443}.
 * @param address Address to add to the allow set if the answer is yes.
 * @param protocol Protocol name, for example {@code tcp}.
 */
public record ClearanceRequest(String project, String destination, String address, String protocol) {

    /**
     * Returns the one-line summary shown in a notification.
     *
     * @return Summary.
     */
    public String summary() {
        return "Sokar: " + project + " blocked";
    }

    /**
     * Returns the body shown in a notification.
     *
     * @return Body.
     */
    public String body() {
        return "The agent tried to reach " + destination + " over " + protocol + ".";
    }
}
