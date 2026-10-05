package org.fuin.sokar.shield;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NflogReader}'s message construction.
 * <p>
 * Binding a real socket needs {@code CAP_NET_ADMIN} in the owning user namespace, which a test
 * process does not have. The bind was verified by hand inside a container namespace; what matters
 * here is that the bytes sent are the bytes the kernel accepted.
 */
class NflogReaderTest {

    @Test
    void buildsTheBindMessageTheKernelAccepts() {

        final byte[] message = NflogReader.bindMessage(1);
        final ByteBuffer buffer = ByteBuffer.wrap(message).order(ByteOrder.nativeOrder());

        assertThat(message).hasSize(28);
        assertThat(buffer.getInt(0)).as("nlmsg_len").isEqualTo(28);
        assertThat(buffer.getShort(4)).as("NFNL_SUBSYS_ULOG << 8 | NFULNL_MSG_CONFIG")
                .isEqualTo((short) 0x0401);
        assertThat(buffer.getShort(6)).as("NLM_F_REQUEST | NLM_F_ACK").isEqualTo((short) 5);
    }

    @Test
    void putsTheGroupNumberInNetworkByteOrder() {

        // res_id is big endian while the netlink header around it is host endian. Getting this
        // wrong binds group 256 instead of group 1 and the reader then sees nothing at all.
        // res_id sits at offset 18: after the 16-byte nlmsghdr come family and version.
        final ByteBuffer buffer = ByteBuffer.wrap(NflogReader.bindMessage(1)).order(ByteOrder.BIG_ENDIAN);

        assertThat(buffer.getShort(18)).isEqualTo((short) 1);
    }

    @Test
    void carriesTheBindCommand() {

        final byte[] message = NflogReader.bindMessage(7);

        assertThat(message[20]).as("nlattr length").isEqualTo((byte) 8);
        assertThat(message[22]).as("NFULA_CFG_CMD").isEqualTo((byte) 1);
        assertThat(message[24]).as("NFULNL_CFG_CMD_BIND").isEqualTo((byte) 1);
    }
}
