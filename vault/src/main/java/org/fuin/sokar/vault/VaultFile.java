package org.fuin.sokar.vault;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.fuin.sokar.wire.Json;

/**
 * The credential store: one AES-256-GCM encrypted file holding a JSON document.
 * <p>
 * Not a database. Everything a database was providing here - atomic writes, crash safety,
 * concurrent access - is provided instead by writing a temporary file, {@code fsync}ing it and
 * renaming it into place, under an advisory lock. The vault holds tens of entries and is written
 * rarely, so the rest of a database is cost without benefit.
 * <p>
 * The file layout is:
 * <pre>
 * magic "SOKARVLT" | version | KDF params | salt | nonce | AEAD ciphertext+tag
 * </pre>
 * The header is authenticated as additional data, so downgrading the KDF parameters of an existing
 * file is not something an attacker can do without invalidating the tag.
 */
public class VaultFile {

    /** Magic bytes at the start of every vault file. */
    static final byte[] MAGIC = "SOKARVLT".getBytes(StandardCharsets.US_ASCII);

    /** Current file format version. */
    static final int VERSION = 1;

    private static final int SALT_LENGTH = 16;

    private static final int NONCE_LENGTH = 12;

    private static final int TAG_BITS = 128;

    private static final int KEY_LENGTH = 32;

    /** Argon2id iterations. */
    static final int ITERATIONS = 3;

    /** Argon2id memory in kibibytes. */
    static final int MEMORY_KIB = 64 * 1024;

    /** Argon2id parallelism. */
    static final int PARALLELISM = 4;

    /** Largest iteration count that will be accepted from a file. */
    static final int MAX_ITERATIONS = 32;

    /** Largest memory cost in kibibytes that will be accepted from a file: 1 GiB. */
    static final int MAX_MEMORY_KIB = 1024 * 1024;

    /** Largest parallelism that will be accepted from a file. */
    static final int MAX_PARALLELISM = 64;

    private static final int HEADER_LENGTH =
            MAGIC.length + 4 + 4 + 4 + 4 + SALT_LENGTH + NONCE_LENGTH;

    /**
     * One lock per vault path, held for the life of the process.
     * <p>
     * A {@link FileLock} is owned by the JVM, not by the thread that took it, so two threads in
     * one process asking for the same region get an {@code OverlappingFileLockException} rather
     * than being serialised. This lock covers threads; the file lock covers processes. Both are
     * needed, and neither replaces the other.
     */
    private static final Map<Path, java.util.concurrent.locks.ReentrantLock> THREAD_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Path file;

    private final SecureRandom random = new SecureRandom();

    /**
     * Constructor.
     *
     * @param file Path of the vault file.
     */
    public VaultFile(Path file) {
        this.file = file;
    }

    /**
     * Returns the path of the vault file.
     *
     * @return File path.
     */
    public Path path() {
        return file;
    }

    /**
     * Tells whether the vault exists.
     *
     * @return {@code true} if the file is there.
     */
    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /**
     * Reads and decrypts the vault.
     *
     * @param passphrase The passphrase.
     * @return The entries, in the order they were written.
     * @throws VaultException If the file is unreadable, malformed, or the passphrase is wrong.
     */
    public Map<String, String> read(char[] passphrase) {

        final byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new VaultException("Cannot read " + file, ex);
        }
        if (content.length < HEADER_LENGTH) {
            throw new VaultException(file + " is too short to be a vault");
        }

        final ByteBuffer buffer = ByteBuffer.wrap(content);
        final byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new VaultException(file + " is not a Sokar vault");
        }
        final int version = buffer.getInt();
        if (version != VERSION) {
            throw new VaultException("Unsupported vault version " + version + ", expected " + VERSION);
        }
        final int iterations = buffer.getInt();
        final int memory = buffer.getInt();
        final int parallelism = buffer.getInt();
        checkCost(iterations, memory, parallelism);
        final byte[] salt = new byte[SALT_LENGTH];
        buffer.get(salt);
        final byte[] nonce = new byte[NONCE_LENGTH];
        buffer.get(nonce);

        final byte[] cipherText = new byte[buffer.remaining()];
        buffer.get(cipherText);

        final byte[] key = deriveKey(passphrase, salt, iterations, memory, parallelism);
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            // The header is authenticated, so the KDF parameters cannot be downgraded in place.
            cipher.updateAAD(content, 0, HEADER_LENGTH);
            return parse(new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            // Deliberately does not distinguish a wrong passphrase from a damaged file: both mean
            // the tag did not verify, and guessing which would be guessing.
            throw new VaultException("Cannot decrypt " + file
                    + " - wrong passphrase, or the file has been altered");
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Encrypts and writes the vault, replacing any previous content.
     *
     * @param entries What to store.
     * @param passphrase The passphrase.
     * @throws VaultException If the file cannot be written.
     */
    public void write(Map<String, String> entries, char[] passphrase) {

        final byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        final byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);

        final ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH);
        header.put(MAGIC);
        header.putInt(VERSION);
        header.putInt(ITERATIONS);
        header.putInt(MEMORY_KIB);
        header.putInt(PARALLELISM);
        header.put(salt);
        header.put(nonce);

        final byte[] key = deriveKey(passphrase, salt, ITERATIONS, MEMORY_KIB, PARALLELISM);
        final byte[] cipherText;
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header.array());
            cipherText = cipher.doFinal(Json.write(entries).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new VaultException("Cannot encrypt the vault", ex);
        } finally {
            Arrays.fill(key, (byte) 0);
        }

        atomicWrite(header.array(), cipherText);
    }

    /**
     * Refuses KDF parameters that no Sokar version would have written.
     * <p>
     * <strong>These are read from the file and used before the authentication tag can be
     * checked</strong> - deriving the key is what produces the ability to check it, so there is no
     * ordering that avoids this. Anyone who can write the file can therefore choose the cost, and
     * a single flipped byte turns three iterations into sixteen million: the process hangs, or
     * allocates until it is killed. Bounding the values is the only defence, and it has to happen
     * here rather than after decryption.
     *
     * @param iterations Iteration count from the file.
     * @param memoryKib Memory cost from the file.
     * @param parallelism Parallelism from the file.
     */
    private void checkCost(int iterations, int memoryKib, int parallelism) {
        if (iterations < 1 || iterations > MAX_ITERATIONS
                || memoryKib < 8 || memoryKib > MAX_MEMORY_KIB
                || parallelism < 1 || parallelism > MAX_PARALLELISM) {
            throw new VaultException(file + " asks for implausible key-derivation parameters"
                    + " (iterations=" + iterations + ", memory=" + memoryKib + " KiB"
                    + ", parallelism=" + parallelism + ") and has been rejected unread");
        }
    }

    private void atomicWrite(byte[] header, byte[] cipherText) {

        final Path directory = file.toAbsolutePath().getParent();
        try {
            Files.createDirectories(directory);
            final Path temporary = Files.createTempFile(directory, ".vault", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (FileChannel channel = FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(header));
                channel.write(ByteBuffer.wrap(cipherText));
                // Without the force() the rename can be durable while the content is not, which
                // on a crash leaves an empty vault where a good one used to be.
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new VaultException("Cannot write " + file, ex);
        }
    }

    /**
     * Runs a read-modify-write cycle under an advisory lock.
     * <p>
     * The lock is held across the whole cycle, so two {@code sokar} processes storing different
     * credentials at the same time cannot lose one of them.
     *
     * @param passphrase The passphrase.
     * @param change Receives the current entries and returns what should be stored.
     * @throws VaultException If the vault cannot be locked, read or written.
     */
    public void update(char[] passphrase, java.util.function.UnaryOperator<Map<String, String>> change) {

        final Path absolute = file.toAbsolutePath();
        final Path lockFile = absolute.resolveSibling(absolute.getFileName() + ".lock");

        final java.util.concurrent.locks.ReentrantLock threadLock = THREAD_LOCKS.computeIfAbsent(
                absolute, key -> new java.util.concurrent.locks.ReentrantLock());

        threadLock.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(lockFile.toFile(), "rw");
                    FileLock lock = raf.getChannel().lock()) {
                final Map<String, String> current =
                        exists() ? new LinkedHashMap<>(read(passphrase)) : new LinkedHashMap<>();
                write(change.apply(current), passphrase);
            }
        } catch (IOException ex) {
            throw new VaultException("Cannot lock " + lockFile, ex);
        } finally {
            threadLock.unlock();
        }
    }

    private static byte[] deriveKey(char[] passphrase, byte[] salt,
            int iterations, int memoryKib, int parallelism) {

        final Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKib)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();

        final Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        final byte[] key = new byte[KEY_LENGTH];
        generator.generateBytes(passphrase, key);
        return key;
    }

    private static Map<String, String> parse(String json) {
        if (!(Json.parse(json) instanceof Map<?, ?> map)) {
            throw new VaultException("The vault does not contain a JSON object");
        }
        final Map<String, String> entries = new LinkedHashMap<>();
        map.forEach((key, value) -> entries.put(String.valueOf(key), String.valueOf(value)));
        return entries;
    }
}
