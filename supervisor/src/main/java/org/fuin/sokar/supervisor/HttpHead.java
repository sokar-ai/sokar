package org.fuin.sokar.supervisor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The request line and headers of one HTTP/1.1 request.
 * <p>
 * Hand-parsed because {@code com.sun.net.httpserver.HttpServer} binds an
 * {@link java.net.InetSocketAddress} and cannot listen on a unix socket, which is the transport
 * the vault proxy uses. Only what a proxy needs is implemented: enough to read a request, decide
 * on it, and reissue it.
 *
 * @param method Request method.
 * @param target Request target, the path and query as sent.
 * @param names Header names in the order received.
 * @param values Header values, positionally matching {@link #names()}.
 */
public record HttpHead(String method, String target, List<String> names, List<String> values) {

    /** Largest head accepted, in bytes. Beyond this a client is confused or hostile. */
    private static final int LIMIT = 64 * 1024;

    /**
     * Constructor with all data.
     *
     * @param method Request method.
     * @param target Request target.
     * @param names Header names.
     * @param values Header values.
     */
    public HttpHead {
        names = List.copyOf(names);
        values = List.copyOf(values);
        if (names.size() != values.size()) {
            throw new IllegalArgumentException("Header names and values must pair up");
        }
    }

    /**
     * Reads a request head from a stream, stopping after the blank line that ends it.
     *
     * @param in Stream positioned at the start of a request.
     * @return The head, or {@code null} when the peer closed without sending one.
     * @throws IOException On a read failure or a malformed head.
     */
    public static HttpHead read(InputStream in) throws IOException {

        final String requestLine = line(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        final String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new IOException("Malformed request line '" + requestLine + "'");
        }

        final List<String> names = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        String header;
        while ((header = line(in)) != null && !header.isEmpty()) {
            final int colon = header.indexOf(':');
            if (colon < 1) {
                throw new IOException("Malformed header '" + header + "'");
            }
            names.add(header.substring(0, colon).strip());
            values.add(header.substring(colon + 1).strip());
        }
        return new HttpHead(parts[0], parts[1], names, values);
    }

    /**
     * Returns the first value of a header, matched without regard to case.
     *
     * @param name Header name.
     * @return Value, or {@code null} when the header is absent.
     */
    public String value(String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return values.get(i);
            }
        }
        return null;
    }

    /**
     * Returns the declared body length.
     *
     * @return Length, or {@code -1} when no usable {@code Content-Length} is present.
     */
    public long contentLength() {
        final String value = value("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * Returns whether the body arrives in chunks rather than with a declared length.
     *
     * @return {@code true} when {@code Transfer-Encoding} names chunked.
     */
    public boolean chunked() {
        final String value = value("Transfer-Encoding");
        return value != null && value.toLowerCase(Locale.ROOT).contains("chunked");
    }

    private static String line(InputStream in) throws IOException {
        final StringBuilder text = new StringBuilder();
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') {
                final int end = text.length() - 1;
                if (end >= 0 && text.charAt(end) == '\r') {
                    text.setLength(end);
                }
                return text.toString();
            }
            text.append((char) read);
            if (text.length() > LIMIT) {
                throw new IOException("Request head exceeds " + LIMIT + " bytes");
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    /**
     * Reads a body that arrived in chunks into a single array.
     *
     * @param in Stream positioned at the first chunk size.
     * @param limit Largest body accepted, in bytes.
     * @return The body.
     * @throws IOException On a read failure, a malformed chunk, or a body over the limit.
     */
    public static byte[] readChunked(InputStream in, int limit) throws IOException {
        final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        while (true) {
            final String header = line(in);
            if (header == null) {
                throw new IOException("Truncated chunked body");
            }
            final int semicolon = header.indexOf(';');
            final String size = (semicolon < 0 ? header : header.substring(0, semicolon)).strip();
            final int length;
            try {
                length = Integer.parseInt(size, 16);
            } catch (NumberFormatException ex) {
                throw new IOException("Malformed chunk size '" + size + "'");
            }
            if (length == 0) {
                // Trailers, then the final blank line. Neither is forwarded.
                String trailer;
                while ((trailer = line(in)) != null && !trailer.isEmpty()) {
                    continue;
                }
                return body.toByteArray();
            }
            if (body.size() + length > limit) {
                throw new IOException("Chunked body exceeds " + limit + " bytes");
            }
            body.write(in.readNBytes(length));
            line(in);
        }
    }

    /**
     * Renders a minimal response, used for everything the proxy answers itself.
     *
     * @param status Status code.
     * @param reason Reason phrase.
     * @param body Body text, sent as {@code application/json}.
     * @return Bytes to write.
     */
    public static byte[] response(int status, String reason, String body) {
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        return ("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }
}
