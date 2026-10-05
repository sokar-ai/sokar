package org.fuin.sokar.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VaultProxy#assess}: a request that asks for a token is refused however it is written, and one the
 * broker cannot read is refused rather than forwarded unread.
 */
class TokenGrantReadingTest {

    private static final byte[] GRANT = "grant_type=refresh_token".getBytes(StandardCharsets.UTF_8);

    @Test
    void aGrantInTheQueryIsRefusedWithOrWithoutABody() {

        // Only the body was read: 'POST /token?grant_type=refresh_token' went out with the real credential.
        assertThat(VaultProxy.assess("/oauth/token?grant_type=refresh_token", new byte[0], null))
                .isEqualTo(VaultProxy.Reading.MINTS);
        assertThat(VaultProxy.assess("/oauth/token?grant%5Ftype=client_credentials", "{}".getBytes(
                StandardCharsets.UTF_8), null)).isEqualTo(VaultProxy.Reading.MINTS);
        assertThat(VaultProxy.assess("/v1/messages?beta=true", new byte[0], null)).isEqualTo(VaultProxy.Reading.CLEAN);
    }

    @Test
    void aGrantBehindMoreThanTheFirstKilobytesOfACompressedBodyIsFound() throws IOException {

        // A compressed body was read to its first 8 KiB only, and forwarded whole.
        final byte[] padded = padded(10_000);
        assertThat(VaultProxy.assess("/token", gzip(padded), "gzip")).isEqualTo(VaultProxy.Reading.MINTS);
        assertThat(VaultProxy.assess("/token", deflate(padded), "deflate")).isEqualTo(VaultProxy.Reading.MINTS);
    }

    @Test
    void aZstdBodyIsReadAndAGrantInItIsRefusedWhileACleanOneGoesOn() {

        // An agent compresses its requests with zstd, which was forwarded unread.
        assertThat(VaultProxy.assess("/token", zstd(padded(20_000)), "zstd")).isEqualTo(VaultProxy.Reading.MINTS);
        assertThat(VaultProxy.assess("/v1/responses", zstd("{\"input\":\"hello\"}".getBytes(StandardCharsets.UTF_8)),
                "zstd")).isEqualTo(VaultProxy.Reading.CLEAN);
    }

    @Test
    void anEncodingTheBrokerCannotReadIsRefusedNotForwarded() throws IOException {

        // Read as raw bytes and forwarded: 'br', two codings stacked, or a body that does not unpack.
        assertThat(VaultProxy.assess("/token", GRANT, "br")).isEqualTo(VaultProxy.Reading.UNSUPPORTED);
        assertThat(VaultProxy.assess("/token", gzip(gzip(GRANT)), "gzip, gzip"))
                .isEqualTo(VaultProxy.Reading.UNSUPPORTED);
        assertThat(VaultProxy.assess("/token", new byte[] {1, 2, 3, 4}, "gzip"))
                .isEqualTo(VaultProxy.Reading.UNREADABLE);
        assertThat(VaultProxy.assess("/v1/messages", "{}".getBytes(StandardCharsets.UTF_8), "identity"))
                .isEqualTo(VaultProxy.Reading.CLEAN);
    }

    @Test
    void aBodyThatUnpacksBeyondTheLimitIsRefused() throws IOException {

        // A small compressed body can unpack to anything; read in full, it is read up to the limit and no further.
        final byte[] large = new byte[VaultProxy.BODY_LIMIT + 1024];
        assertThat(VaultProxy.assess("/v1/messages", gzip(large), "gzip")).isEqualTo(VaultProxy.Reading.TOO_LARGE);
    }

    private static byte[] padded(int size) {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("pad=" + "x".repeat(size) + "&").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(GRANT);
        return body.toByteArray();
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
            gzip.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] plain) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream deflate = new java.util.zip.DeflaterOutputStream(out)) {
            deflate.write(plain);
        }
        return out.toByteArray();
    }

    private static byte[] zstd(byte[] plain) {
        final io.airlift.compress.v3.zstd.ZstdCompressor compressor = io.airlift.compress.v3.zstd.ZstdCompressor.create();
        final byte[] out = new byte[compressor.maxCompressedLength(plain.length)];
        final int size = compressor.compress(plain, 0, plain.length, out, 0, out.length);
        return java.util.Arrays.copyOf(out, size);
    }
}
