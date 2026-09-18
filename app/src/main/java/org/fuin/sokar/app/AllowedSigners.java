package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who may speak for a peer, read from an OpenSSH {@code allowed_signers} file.
 * <p>
 * The same file {@code ssh-keygen -Y verify} takes, and deliberately so: the keys that decide what
 * a task is allowed to read can be checked by somebody with no Sokar, and written by somebody who
 * has only ever used ssh.
 * <p>
 * <strong>What cannot be read is not silently skipped.</strong> A line this cannot parse makes the
 * whole file refused, because a keyring that quietly lost an entry is a keyring that quietly stops
 * delivering - or, worse, quietly stops recognising the peer whose key was on that line.
 */
public final class AllowedSigners {

    private AllowedSigners() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads the peers and their keys.
     *
     * @param file The file, which need not exist.
     * @return One entry per principal, with every key allowed for it. Empty when there is no file.
     * @throws IOException Reading failed, or a line is not one this understands.
     */
    public static List<MessageDelivery.Peer> read(final Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final Map<String, List<byte[]>> byPrincipal = new LinkedHashMap<>();
        int number = 0;
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            number++;
            final String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            final String[] fields = trimmed.split("\\s+");
            if (fields.length < 3 || !"ssh-ed25519".equals(fields[1])) {
                throw new IOException(file + ":" + number
                        + ": expected '<principal> ssh-ed25519 <key>', got: " + trimmed);
            }
            final byte[] key;
            try {
                key = Base64.getDecoder().decode(fields[2]);
            } catch (final IllegalArgumentException e) {
                throw new IOException(file + ":" + number + ": the key is not base64", e);
            }
            byPrincipal.computeIfAbsent(fields[0], principal -> new ArrayList<>()).add(key);
        }
        final List<MessageDelivery.Peer> peers = new ArrayList<>();
        byPrincipal.forEach((principal, keys) ->
                peers.add(new MessageDelivery.Peer(principal, List.copyOf(keys))));
        return List.copyOf(peers);
    }
}
