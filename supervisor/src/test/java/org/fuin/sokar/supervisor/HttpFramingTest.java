package org.fuin.sokar.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * What the proxy accepts as a body.
 * <p>
 * Everything here used to be accepted and forwarded to the provider under this task's credential,
 * which turns a malformed request into an unexplainable provider error rather than a refusal.
 */
class HttpFramingTest {

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aBodyShorterThanDeclaredIsRefused() throws IOException {

        // 'readNBytes' returns what arrived. What arrived is not what was promised, and sending
        // the difference on as if it were the whole request is the defect.
        assertThatThrownBy(() -> HttpHead.readExactly(bytes("only-9-by"), 20))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ended after 9 of 20");
    }

    @Test
    void exactlyTheDeclaredLengthIsAccepted() throws IOException {
        assertThat(HttpHead.readExactly(bytes("0123456789"), 10))
                .asString(StandardCharsets.UTF_8).isEqualTo("0123456789");
    }

    @Test
    void aChunkNotFollowedByItsBlankLineIsRefused() {

        // Without this the stream's next bytes were read as the following chunk's size, so a
        // truncated body silently became a differently framed one.
        assertThatThrownBy(() -> HttpHead.readChunked(bytes("4\r\nabcdxxxx"), 1024))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aChunkedBodyWithoutItsFinalBlankLineIsRefused() {
        assertThatThrownBy(() -> HttpHead.readChunked(bytes("4\r\nabcd\r\n0\r\n"), 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("final blank line");
    }

    @Test
    void aWellFramedChunkedBodyIsAccepted() throws IOException {
        assertThat(HttpHead.readChunked(bytes("4\r\nabcd\r\n3\r\nefg\r\n0\r\n\r\n"), 1024))
                .asString(StandardCharsets.UTF_8).isEqualTo("abcdefg");
    }

    @Test
    void aChunkThatEndsEarlyIsRefused() {
        assertThatThrownBy(() -> HttpHead.readChunked(bytes("10\r\nshort"), 1024))
                .isInstanceOf(IOException.class);
    }
}
