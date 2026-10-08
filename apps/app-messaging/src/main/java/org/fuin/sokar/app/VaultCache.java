package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultFile;
import org.jspecify.annotations.Nullable;

/**
 * What the daemon's conversations last read from the vault, held while the vault is unchanged and open.
 * <p>
 * Every lookup of a conversation's secrets decrypted the whole vault, and the key derivation costs about 150 ms of CPU
 * a read: each send, each round of every long-poll, several times each pass. With two idle tasks in a conversation the
 * daemon used 18 % of a core. Held here instead, in the daemon only, while {@code vault.bin} keeps its modification
 * time and size, and dropped at once when the vault is found locked or when the daemon's look sees it shut. A command
 * reads the vault as it always did: nothing is held unless the daemon turned this on.
 */
final class VaultCache {

    private static final Object LOCK = new Object();

    private static volatile boolean holding;

    private static @Nullable Kept kept;

    /** Decryptions since the last {@link #counted()}: what the key derivation cost. */
    private static final java.util.concurrent.atomic.AtomicInteger READS = new java.util.concurrent.atomic.AtomicInteger();

    /** Lookups answered from what was held, since the last {@link #counted()}. */
    private static final java.util.concurrent.atomic.AtomicInteger HELD = new java.util.concurrent.atomic.AtomicInteger();

    /** What was read, and the file as it was when it was read. */
    private record Kept(Path file, FileTime modified, long size, Map<String, VaultEntry> entries) {
    }

    /**
     * Returns how the vault was read since the last call, for the daemon's journal, and starts counting again.
     *
     * @return {@code "<n> vault read(s), <m> held"}.
     */
    static String counted() {
        return READS.getAndSet(0) + " vault read(s), " + HELD.getAndSet(0) + " held";
    }

    private VaultCache() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Turns holding on, for the daemon's conversations.
     */
    static void hold() {
        holding = true;
    }

    /**
     * Forgets what was held: the vault was locked, or this is a test.
     */
    static void drop() {
        synchronized (LOCK) {
            kept = null;
        }
    }

    /**
     * Turns holding off and forgets what was held.
     */
    static void stop() {
        holding = false;
        drop();
    }

    /**
     * Returns the vault's entries: what was held when the file is unchanged, else read and held anew.
     *
     * @param vault The vault.
     * @param opener What opens it, read from the keyring now - so a locked vault never gets this far.
     * @return The entries.
     */
    static Map<String, VaultEntry> read(final VaultFile vault, final VaultFile.Opener opener) {
        if (!holding) {
            READS.incrementAndGet();
            return vault.read(opener);
        }
        final BasicFileAttributes now;
        try {
            now = Files.readAttributes(vault.path(), BasicFileAttributes.class);
        } catch (IOException ex) {
            drop();
            READS.incrementAndGet();
            return vault.read(opener);
        }
        synchronized (LOCK) {
            final Kept held = kept;
            if (held != null && held.file().equals(vault.path()) && held.modified().equals(now.lastModifiedTime())
                    && held.size() == now.size()) {
                HELD.incrementAndGet();
                return held.entries();
            }
            READS.incrementAndGet();
            // The attributes from before the read: a file changed during it is read again next time.
            final Map<String, VaultEntry> entries = Map.copyOf(vault.read(opener));
            kept = new Kept(vault.path(), now.lastModifiedTime(), now.size(), entries);
            return entries;
        }
    }
}
