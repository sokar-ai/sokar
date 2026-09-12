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
 * Chunk boundaries are covered by keeping the tail of what was scanned: a name split across two
 * reads is still one name.
 */
final class CredentialScan {

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

    private String carried = "";

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
        return pattern.append("\"\\s*:").toString();
    }

    /**
     * Reports whether a credential field appears in what has been scanned so far.
     *
     * @param chunk The next bytes of the answer.
     * @param length How many of them are used.
     * @return Whether this chunk, or its join with the previous one, carries a credential field.
     */
    boolean sees(byte[] chunk, int length) {
        final String text = carried + new String(chunk, 0, length, StandardCharsets.UTF_8);
        carried = text.length() <= OVERLAP ? text : text.substring(text.length() - OVERLAP);
        return CREDENTIAL_FIELD.matcher(text).find();
    }
}
