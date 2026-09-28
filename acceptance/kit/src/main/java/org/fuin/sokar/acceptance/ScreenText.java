package org.fuin.sokar.acceptance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text a terminal stream shows, without the sequences that position and colour it.
 * <p>
 * <strong>Why a raw match is not enough.</strong> A full-screen program under tmux is redrawn, and tmux
 * writes a gap between words as a cursor move rather than as spaces - {@code bypass\e[Cpermissions} -
 * so text with a space in it is on the screen and not in the stream. Measured with a real agent at
 * its prompt, whose footer never matched. This reads the stream as the text it shows on a line:
 * a cursor move forward is that many spaces, every other control sequence is dropped.
 * <p>
 * Not a terminal emulator: text drawn out of order by jumping back and forth would still not read as
 * one line. For one line of text an agent prints at its prompt it is what the screen shows.
 */
final class ScreenText {

    /** A control sequence introducer: parameters, intermediates and a final byte. */
    private static final Pattern CSI = Pattern.compile("\u001b\\[([0-?]*)([ -/]*)([@-~])");

    /** An operating system command, ended by BEL or by ESC backslash. */
    private static final Pattern OSC = Pattern.compile("\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)");

    /** Any other escape: a character set designation, ESC ( B, or ESC and one more character. */
    private static final Pattern ESCAPE = Pattern.compile("\u001b[()*+\\-./].|\u001b[^\\[\\]]");

    private ScreenText() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a terminal stream as the text it shows.
     *
     * @param stream What the terminal received, escape sequences included.
     * @return The same text with a cursor move forward as spaces and every other sequence removed.
     */
    static String of(String stream) {
        final String withoutOsc = OSC.matcher(stream).replaceAll("");
        final Matcher csi = CSI.matcher(withoutOsc);
        final StringBuilder shown = new StringBuilder();
        while (csi.find()) {
            final String replacement;
            if ("C".equals(csi.group(3)) && csi.group(2).isEmpty()) {
                final String count = csi.group(1);
                replacement = " ".repeat(count.isEmpty() ? 1 : Integer.parseInt(count.split(";")[0]));
            } else {
                replacement = "";
            }
            csi.appendReplacement(shown, Matcher.quoteReplacement(replacement));
        }
        csi.appendTail(shown);
        return ESCAPE.matcher(shown).replaceAll("");
    }
}
