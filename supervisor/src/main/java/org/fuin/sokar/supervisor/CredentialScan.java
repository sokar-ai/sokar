package org.fuin.sokar.supervisor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Watches a provider's answer for a credential on its way to a container.
 * <p>
 * <strong>Every byte, not the first few thousand.</strong> This replaced a check that examined a
 * bounded prefix of the response and passed the rest through unread. A credential field after that
 * prefix reached the container, which is the one thing the phantom token exists to prevent, and a
 * response large enough to hide one is an ordinary completion rather than an exotic case.
 * <p>
 * <strong>Escapes count.</strong> JSON may spell any character of a member name as
 * {@code \\uXXXX}, so {@code "\u0061ccess_token"} is the same field as {@code "access_token"}
 * to every parser and was not the same string to a literal match. Each character of a watched name
 * is therefore matched either as itself or as its escape.
 * <p>
 * <strong>What is deliberately NOT normalized is the structure.</strong> The quotes and the colon
 * around a name must be literal and unescaped. Inside a JSON string a quote arrives as
 * {@code \\"}, so an answer whose <em>content</em> discusses {@code \\"refresh_token\\":} is text
 * about a credential rather than a credential - and decoding escapes far enough to turn that into
 * a match would withhold ordinary answers. The scan is meant to be exact in both directions.
 * <p>
 * Chunk boundaries are covered two ways, because one was not enough.
 * <p>
 * <strong>A name split across two reads</strong> is joined by keeping the tail of what was scanned.
 * <p>
 * <strong>A name separated from its colon by more whitespace than that tail holds</strong> is
 * carried by a flag instead. JSON allows any amount of space between a member name and its colon,
 * and a fixed window is finite by definition: with enough of it the name falls out of the window
 * before the colon arrives, and neither half matches anything. Measured through the proxy - the
 * credential reached the container. So when a chunk ends with a watched name and nothing but
 * whitespace after it, the scan stays <em>waiting for a colon</em> across as many chunks as it
 * takes; it is bounded by a boolean rather than by a buffer, so no length of whitespace defeats it.
 * <p>
 * This is still a lexical guard and not a parser. What it now covers is every framing of the names
 * it watches; what it does not cover is a credential a provider gives a name nobody watches.
 */
final class CredentialScan {

    /**
     * The whitespace JSON allows, and nothing else.
     * <p>
     * Narrower than the pattern's {@code \s}, which also admits a vertical tab and a form feed,
     * and far narrower than {@link Character#isWhitespace}. The wait below and the pattern use this
     * one set on purpose: with two different notions of whitespace the same answer would be judged
     * differently depending on where its chunk boundaries fell, which is the class of defect this
     * scan has already been bitten by twice. What is let through by narrowing is a byte sequence no
     * JSON parser reads as a member name either, so it is not a credential field to anybody.
     */
    private static final String WHITESPACE = "[ \\t\\n\\r]";

    /** The member names whose presence means the answer carries a credential. */
    private static final List<String> NAMES = List.of("access_token", "refresh_token", "id_token");

    /**
     * How much of the previous chunk is rescanned with the next one.
     * <p>
     * The longest watched name is 13 characters, every one of which may arrive as a six-byte
     * escape, plus the quotes, the colon and any spaces between them. 256 is comfortably above
     * that and costs nothing.
     */
    private static final int OVERLAP = 256;

    private static final Pattern CREDENTIAL_FIELD = Pattern.compile(
            NAMES.stream().map(CredentialScan::escapable)
                    .reduce((first, second) -> first + "|" + second).orElseThrow());

    /** A watched name, followed by whitespace, at the very end of what has been scanned. */
    private static final Pattern NAME_AT_THE_END = Pattern.compile(
            "(?:" + NAMES.stream().map(CredentialScan::escapable)
                    .map(each -> each.substring(0, each.length() - (WHITESPACE + "*:").length()))
                    .reduce((first, second) -> first + "|" + second).orElseThrow()
            + ")" + WHITESPACE + "*$");

    private String carried = "";

    /** Set when a watched name has been seen and only whitespace has followed it so far. */
    private boolean awaitingColon;

    /**
     * Builds a pattern matching one member name written plainly or with escaped characters.
     *
     * @param name The member name.
     * @return A regular expression fragment.
     */
    private static String escapable(String name) {
        final StringBuilder pattern = new StringBuilder("(?<!\\\\)\"");
        for (final char character : name.toCharArray()) {
            pattern.append("(?:").append(Pattern.quote(String.valueOf(character)))
                    .append("|\\\\u00").append(String.format("%02x", (int) character))
                    .append("|\\\\u00").append(String.format("%02X", (int) character))
                    .append(')');
        }
        return pattern.append("\"" + WHITESPACE + "*:").toString();
    }

    /**
     * Reports whether a credential field appears in what has been scanned so far.
     *
     * @param chunk The next bytes of the answer.
     * @param length How many of them are used.
     * @return Whether this chunk, or its join with the previous one, carries a credential field.
     */
    boolean sees(byte[] chunk, int length) {
        final String next = new String(chunk, 0, length, StandardCharsets.UTF_8);
        if (awaitingColon) {
            final int at = firstMeaning(next);
            if (at < 0) {
                // Nothing but whitespace again. Still waiting, however long this goes on.
                return false;
            }
            if (next.charAt(at) == ':') {
                return true;
            }
            // Something else followed the name, so it was not a member name after all.
            awaitingColon = false;
        }
        final String text = carried + next;
        if (CREDENTIAL_FIELD.matcher(text).find()) {
            return true;
        }
        awaitingColon = NAME_AT_THE_END.matcher(text).find();
        carried = text.length() <= OVERLAP ? text : text.substring(text.length() - OVERLAP);
        return false;
    }

    /**
     * Finds the first character that is not JSON whitespace.
     * <p>
     * This used {@link Character#isWhitespace}, on the reasoning that a wider notion is the safe
     * direction because waiting withholds. It is the safe direction for confidentiality and the
     * unsafe one for availability: a character JSON does not call whitespace would have kept the
     * scan waiting, and a wait that never ends withholds an answer no parser would object to.
     * The exact grammar is better on both counts, because what it stops waiting for is a sequence
     * no parser reads as a member name.
     *
     * @param text What to look in.
     * @return Its index, or -1 when there is none.
     */
    private static int firstMeaning(String text) {
        for (int at = 0; at < text.length(); at++) {
            final char character = text.charAt(at);
            if (character != ' ' && character != '\t' && character != '\n' && character != '\r') {
                return at;
            }
        }
        return -1;
    }
}
