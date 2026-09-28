package org.fuin.sokar.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A minimal JSON reader and writer for the sidecar file.
 * <p>
 * Hand-written because this module must stay free of dependencies: it is the only code shared with
 * the statically linked hook binaries, and every library it pulled in would either enlarge those
 * binaries or, if it used the Foreign Function &amp; Memory API anywhere, stop them linking at all.
 * <p>
 * It supports objects, arrays, strings, numbers, booleans and null - the whole of the sidecar
 * schema and nothing else. It is not a general-purpose parser and should not become one.
 */
public final class Json {

    private final String text;

    private int position;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Parses a JSON document.
     *
     * @param text The document.
     * @return A {@link Map}, {@link List}, {@link String}, {@link Double}, {@link Boolean} or
     *         {@code null}.
     * @throws JsonException If the text is not well-formed JSON.
     */
    public static @Nullable Object parse(String text) {
        final Json json = new Json(text);
        json.skipWhitespace();
        final Object value = json.readValue();
        json.skipWhitespace();
        if (json.position < text.length()) {
            throw json.error("Trailing content");
        }
        return value;
    }

    /**
     * Writes a value as JSON.
     *
     * @param value A {@link Map}, {@link List}, {@link CharSequence}, {@link Number},
     *        {@link Boolean} or {@code null}.
     * @return The document, without a trailing newline.
     */
    public static String write(@Nullable Object value) {
        final StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    /**
     * Writes a value into a buffer the caller owns.
     * <p>
     * Exists for the vault. {@link #write(Object)} hands back a {@code String}, which for the
     * vault's document is every credential it holds in plaintext, immutable and impossible to
     * clear. A caller that owns the buffer can overwrite it once the bytes have been encrypted.
     *
     * @param value Value to write.
     * @param out Where to write it.
     */
    public static void write(@Nullable Object value, StringBuilder out) {
        writeValue(out, value);
    }

    private static void writeValue(StringBuilder out, @Nullable Object value) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (final Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    writeValue(out, entry.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    writeValue(out, list.get(i));
                }
                out.append(']');
            }
            case CharSequence text -> writeString(out, text.toString());
            case Boolean flag -> out.append(flag.booleanValue());
            case Number number -> out.append(number);
            default -> throw new JsonException("Cannot write a " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private @Nullable Object readValue() {
        if (position >= text.length()) {
            throw error("Unexpected end of input");
        }
        final char c = text.charAt(position);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readKeyword("true", Boolean.TRUE);
            case 'f' -> readKeyword("false", Boolean.FALSE);
            case 'n' -> readKeyword("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        final Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return map;
        }
        while (true) {
            skipWhitespace();
            final String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            if (map.put(key, readValue()) != null) {
                // A duplicate key would let a second value quietly win. In a file that tells a
                // fail-closed hook what to do, that is not a formatting curiosity.
                throw error("Duplicate key '" + key + "'");
            }
            skipWhitespace();
            final char next = next();
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                throw error("Expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        final List<Object> list = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            final char next = next();
            if (next == ']') {
                return list;
            }
            if (next != ',') {
                throw error("Expected ',' or ']'");
            }
        }
    }

    private String readString() {
        expect('"');
        final StringBuilder value = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw error("Unterminated string");
            }
            final char c = text.charAt(position++);
            if (c == '"') {
                return value.toString();
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            final char escape = next();
            switch (escape) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw error("Truncated unicode escape");
                    }
                    value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> throw error("Unknown escape '\\" + escape + "'");
            }
        }
    }

    private @Nullable Object readKeyword(String keyword, @Nullable Object value) {
        if (!text.startsWith(keyword, position)) {
            throw error("Expected '" + keyword + "'");
        }
        position += keyword.length();
        return value;
    }

    private Double readNumber() {
        final int start = position;
        while (position < text.length() && "+-.eE0123456789".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        if (start == position) {
            throw error("Expected a value");
        }
        try {
            return Double.valueOf(text.substring(start, position));
        } catch (NumberFormatException ex) {
            throw error("Not a number: '" + text.substring(start, position) + "'");
        }
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("Unexpected end of input");
        }
        return text.charAt(position);
    }

    private char next() {
        final char c = peek();
        position++;
        return c;
    }

    private void expect(char expected) {
        if (next() != expected) {
            position--;
            throw error("Expected '" + expected + "'");
        }
    }

    private JsonException error(String message) {
        return new JsonException(message + " at offset " + position);
    }
}
