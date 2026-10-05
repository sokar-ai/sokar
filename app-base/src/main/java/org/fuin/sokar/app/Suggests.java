package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;

/**
 * A command that can say which names it would have accepted.
 * <p>
 * <strong>The name is not the hard part; finding it is.</strong> Every command here takes an
 * argument naming something Sokar already knows about - a container, a project, an agent - and
 * refusing with "missing required parameter" makes somebody go and run another command to read
 * back a thirty-eight character name they then retype. What the machine knows, the refusal
 * should say.
 */
public interface Suggests {

    /**
     * Returns the names this command would have taken, in the order to show them.
     *
     * @return Candidates, empty when there are none right now.
     */
    List<String> candidates();

    /**
     * Returns what those names are, as the noun in a sentence offering them - "stopped tasks".
     *
     * @return Plural noun phrase.
     */
    String candidateLabel();

    /**
     * Writes the candidates, or says there are none.
     * <p>
     * <strong>An empty list is said out loud.</strong> Printing nothing would read as a command
     * that had nothing to add, when what it means is that there is nothing on this machine to
     * name - which is usually the actual answer somebody needed.
     * <p>
     * Never throws. This runs while a command is already failing, and asking the runtime can fail
     * too; a stack trace here would replace a useful message with a worse one.
     *
     * @param err Where to write.
     * @param command The command that was refused.
     */
    static void offer(PrintWriter err, Suggests command) {
        try {
            final List<String> candidates = command.candidates();
            if (candidates.isEmpty()) {
                err.println("sokar: there are no " + command.candidateLabel()
                        + " on this machine");
            } else {
                err.println("sokar: the " + command.candidateLabel() + " here are:");
                candidates.forEach(name -> err.println("         " + name));
            }
        } catch (RuntimeException ex) {
            // Nothing to add is better than something wrong, and the refusal above still stands.
        }
    }
}
