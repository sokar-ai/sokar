package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds a file's lock across threads and processes: the daemon's pass and a command at the terminal work on one
 * mailbox, and a lock of either kind alone leaves the other way open.
 */
final class FileLocks {

    /** One lock per file in this process: a second channel's lock of the same file throws instead of waiting. */
    private static final ConcurrentHashMap<Path, ReentrantLock> HELD = new ConcurrentHashMap<>();

    private FileLocks() {
    }

    /** What runs while the lock is held. */
    @FunctionalInterface
    interface Locked<T> {

        /**
         * Runs.
         *
         * @return Its answer.
         * @throws IOException Reading or writing failed.
         */
        T run() throws IOException;
    }

    /**
     * Runs something while holding the lock on a file, made if it is not there.
     *
     * @param <T> What it answers.
     * @param lock The lock file.
     * @param locked What runs.
     * @return Its answer.
     * @throws IOException Locking, reading or writing failed.
     */
    static <T> T holding(final Path lock, final Locked<T> locked) throws IOException {
        final ReentrantLock here = HELD.computeIfAbsent(lock.toAbsolutePath().normalize(), path -> new ReentrantLock());
        here.lock();
        try {
            if (here.getHoldCount() > 1) {
                return locked.run();
            }
            Files.createDirectories(lock.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock held = channel.lock()) {
                return locked.run();
            }
        } finally {
            here.unlock();
        }
    }
}
