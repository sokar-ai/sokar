package org.fuin.sokar.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Every key this machine will believe a message from, from both places it can come.
 * <p>
 * One place, because there are two sources and everything that delivers a message has to consult
 * both: the operator's own {@code allowed_signers}, which names peers on other machines, and the
 * keys the other Unix users of <em>this</em> machine have published to each other. A caller that
 * read only the first would refuse every message from the account next door with "signed by a key
 * no peer is allowed to use", which is true and useless.
 * <p>
 * <strong>The file wins on a name they both claim.</strong> The operator wrote that one down by
 * hand; the shared directory fills itself from whoever is on the machine. Where they disagree, the
 * deliberate one is the one to keep.
 */
public final class KnownPeers {

    private KnownPeers() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads both sources.
     *
     * @param context The machine.
     * @return The peers, the operator's first.
     * @throws IOException Reading either source failed. A keyring with a line nobody can read
     *         stops everything rather than quietly admitting fewer peers.
     */
    public static List<MessageDelivery.Peer> of(final SokarContext context) throws IOException {
        return of(context.paths().allowedSigners(), context.paths().sharedKeys());
    }

    /**
     * Reads both sources, named.
     * <p>
     * The paths rather than the machine, so this can be tested against two directories without
     * pretending to be a machine.
     *
     * @param allowedSigners The operator's own keyring file.
     * @param sharedKeys Where the accounts of this machine publish to each other.
     * @return The peers, the operator's first.
     * @throws IOException Reading either source failed.
     */
    static List<MessageDelivery.Peer> of(final java.nio.file.Path allowedSigners,
            final java.nio.file.Path sharedKeys) throws IOException {
        final List<MessageDelivery.Peer> peers = new ArrayList<>(AllowedSigners.read(allowedSigners));
        final List<String> named = peers.stream().map(MessageDelivery.Peer::name).toList();
        for (final MessageDelivery.Peer shared : SharedKeys.read(sharedKeys)) {
            if (!named.contains(shared.name())) {
                peers.add(shared);
            }
        }
        return List.copyOf(peers);
    }
}
