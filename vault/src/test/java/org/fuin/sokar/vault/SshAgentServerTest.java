package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SshAgentServer}'s message handling.
 * <p>
 * The wire format is also checked against real OpenSSH in a separate manual run; what these tests
 * pin is the behaviour that must not drift, above all the requests the agent refuses.
 */
class SshAgentServerTest {

    private final SigningKey key = SigningKey.generate("sokar-test");

    private SshAgentServer agent(Path dir) {
        return new SshAgentServer(dir.resolve("agent.sock"), List.of(key));
    }

    private static byte[] request(byte type, byte[]... strings) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        for (final byte[] value : strings) {
            SshWire.writeString(out, value);
        }
        return out.toByteArray();
    }

    @Test
    void listsTheKeysItHolds(@TempDir Path dir) throws IOException {

        try (SshAgentServer server = agent(dir)) {

            final ByteBuffer response = ByteBuffer.wrap(
                    server.handle(new byte[] { SshAgentServer.SSH_AGENTC_REQUEST_IDENTITIES }));

            assertThat(response.get()).isEqualTo(SshAgentServer.SSH_AGENT_IDENTITIES_ANSWER);
            assertThat(response.getInt()).isEqualTo(1);
            assertThat(SshWire.readString(response)).isEqualTo(key.keyBlob());
            assertThat(new String(SshWire.readString(response), StandardCharsets.UTF_8))
                    .isEqualTo("sokar-test");
        }
    }

    @Test
    void signsWithAKeyItHolds(@TempDir Path dir) throws IOException {

        try (SshAgentServer server = agent(dir)) {

            final byte[] data = "some bytes to sign".getBytes(StandardCharsets.UTF_8);
            final ByteBuffer response = ByteBuffer.wrap(server.handle(
                    request(SshAgentServer.SSH_AGENTC_SIGN_REQUEST, key.keyBlob(), data)));

            assertThat(response.get()).isEqualTo(SshAgentServer.SSH_AGENT_SIGN_RESPONSE);
            assertThat(key.verify(data, SshWire.readString(response))).isTrue();
        }
    }

    @Test
    void refusesToSignForAKeyItDoesNotHold(@TempDir Path dir) throws IOException {

        try (SshAgentServer server = agent(dir)) {

            final SigningKey other = SigningKey.generate("someone-else");
            final byte[] response = server.handle(request(SshAgentServer.SSH_AGENTC_SIGN_REQUEST,
                    other.keyBlob(), "data".getBytes(StandardCharsets.UTF_8)));

            assertThat(response).containsExactly(SshAgentServer.SSH_AGENT_FAILURE);
        }
    }

    @Test
    void refusesEveryRequestItWasNotBuiltFor(@TempDir Path dir) {

        try (SshAgentServer server = agent(dir)) {

            // 17 is SSH_AGENTC_ADD_IDENTITY. Accepting keys over this socket is exactly what must
            // not be possible: the container would then be able to make the agent sign for a key
            // of its own choosing. Every unknown request is refused rather than guessed at.
            for (final byte type : new byte[] { 17, 18, 19, 20, 21, 22, 25, 27, 0, -1 }) {
                assertThat(server.handle(new byte[] { type }))
                        .as("request type %s", type)
                        .containsExactly(SshAgentServer.SSH_AGENT_FAILURE);
            }
        }
    }

    @Test
    void refusesATruncatedRequestRatherThanFailing(@TempDir Path dir) {

        try (SshAgentServer server = agent(dir)) {

            assertThat(server.handle(new byte[] { SshAgentServer.SSH_AGENTC_SIGN_REQUEST }))
                    .containsExactly(SshAgentServer.SSH_AGENT_FAILURE);
            assertThat(server.handle(new byte[] { SshAgentServer.SSH_AGENTC_SIGN_REQUEST, 0, 0, 0 }))
                    .containsExactly(SshAgentServer.SSH_AGENT_FAILURE);
        }
    }

    @Test
    void refusesAStringLongerThanTheMessageThatContainsIt(@TempDir Path dir) {

        try (SshAgentServer server = agent(dir)) {

            // A length field a client controls is how a parser becomes an allocator.
            final ByteBuffer request = ByteBuffer.allocate(9);
            request.put(SshAgentServer.SSH_AGENTC_SIGN_REQUEST);
            request.putInt(Integer.MAX_VALUE);
            request.putInt(0);

            assertThat(server.handle(request.array()))
                    .containsExactly(SshAgentServer.SSH_AGENT_FAILURE);
        }
    }

    @Test
    void createsTheSocketWithOwnerOnlyPermissions(@TempDir Path dir) throws IOException {

        // Anyone who can open the socket can sign with the key, so the permissions are the access
        // control.
        try (SshAgentServer server = agent(dir)) {

            assertThat(java.nio.file.Files.getPosixFilePermissions(server.socketPath()))
                    .containsExactlyInAnyOrder(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        }
    }

    @Test
    void removesTheSocketWhenItStops(@TempDir Path dir) {

        final Path socket;
        try (SshAgentServer server = agent(dir)) {
            socket = server.socketPath();
            assertThat(socket).exists();
        }
        assertThat(socket).doesNotExist();
    }
}
