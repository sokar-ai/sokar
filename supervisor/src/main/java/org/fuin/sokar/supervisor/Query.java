package org.fuin.sokar.supervisor;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The query string of a request target, as the broker needs it: read one parameter, replace it, and
 * never write it to a log.
 * <p>
 * <strong>A key in the URL is a key in the log.</strong> The broker logs one line per request, and a
 * target carries its query string. This project has paid once already for a token printed in full, so
 * every logged target has its query reduced to the names of its parameters.
 */
final class Query {

    private Query() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns one parameter's value, decoded.
     *
     * @param target The request target, path and query.
     * @param name The parameter.
     * @return Its first value, or {@code null} when it is absent.
     */
    static @Nullable String value(String target, String name) {
        for (final String pair : pairs(target)) {
            final int equals = pair.indexOf('=');
            final String key = decode(equals < 0 ? pair : pair.substring(0, equals));
            if (key.equals(name)) {
                return equals < 0 ? "" : decode(pair.substring(equals + 1));
            }
        }
        return null;
    }

    /**
     * Returns the target with every occurrence of a parameter removed and one with the given value added.
     *
     * @param target The request target.
     * @param name The parameter.
     * @param value Its new value, not yet encoded.
     * @return The new target.
     */
    static String replace(String target, String name, String value) {
        final int question = target.indexOf('?');
        final String path = question < 0 ? target : target.substring(0, question);
        final List<String> kept = new ArrayList<>();
        for (final String pair : pairs(target)) {
            final int equals = pair.indexOf('=');
            if (!decode(equals < 0 ? pair : pair.substring(0, equals)).equals(name)) {
                kept.add(pair);
            }
        }
        kept.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        return path + "?" + String.join("&", kept);
    }

    /**
     * Returns the target without any of the given parameters.
     *
     * @param target The request target.
     * @param names The parameters to take out.
     * @return The target without them; unchanged when it carries none.
     */
    static String without(String target, java.util.Set<String> names) {
        if (names.isEmpty() || target.indexOf('?') < 0) {
            return target;
        }
        final int question = target.indexOf('?');
        final List<String> kept = new ArrayList<>();
        for (final String pair : pairs(target)) {
            final int equals = pair.indexOf('=');
            if (!names.contains(decode(equals < 0 ? pair : pair.substring(0, equals)))) {
                kept.add(pair);
            }
        }
        return kept.isEmpty() ? target.substring(0, question) : target.substring(0, question) + "?" + String.join("&", kept);
    }

    /**
     * Returns the target as a log may show it: the path, and the query's parameter names without values.
     *
     * @param target The request target.
     * @return The target, with no value of any parameter in it.
     */
    static String redacted(String target) {
        final int question = target.indexOf('?');
        if (question < 0) {
            return target;
        }
        final List<String> names = new ArrayList<>();
        for (final String pair : pairs(target)) {
            final int equals = pair.indexOf('=');
            names.add((equals < 0 ? pair : pair.substring(0, equals)) + "=…");
        }
        return target.substring(0, question) + "?" + String.join("&", names);
    }

    private static List<String> pairs(String target) {
        final int question = target.indexOf('?');
        if (question < 0 || question == target.length() - 1) {
            return List.of();
        }
        final List<String> pairs = new ArrayList<>();
        for (final String pair : target.substring(question + 1).split("&")) {
            if (!pair.isEmpty()) {
                pairs.add(pair);
            }
        }
        return pairs;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return text;
        }
    }
}
