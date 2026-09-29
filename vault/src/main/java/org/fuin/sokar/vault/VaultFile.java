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
import org.jspecify.annotations.Nullable;

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
 * magic "SOKARVLT" | version | header length | header JSON | content nonce | AEAD ciphertext+tag
 * </pre>
 * The header is authenticated as additional data, so nothing in it can be changed without
 * invalidating the tag - not the KDF parameters, not a wrapped key, not a device's name.
 * <p>
 * <strong>The content is encrypted under a master key that no credential is.</strong> The master
 * key is generated once, when the vault is created, and stored only in wrapped form - once per
 * {@link Keyslot}. The passphrase is keyslot 0 and wraps it with Argon2id; a device's share wraps
 * it with HKDF. Adding a device adds a slot, removing one deletes a slot, and neither re-keys
 * anything or disturbs another device.
 */
public class VaultFile {

    private static final int SALT_LENGTH = 16;

    static final int NONCE_LENGTH = 12;

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

    /**
     * One lock per vault path, held for the life of the process.
     * <p>
     * A {@link FileLock} is owned by the JVM, not by the thread that took it, so two threads in
     * one process asking for the same region get an {@code OverlappingFileLockException} rather
     * than being serialized. This lock covers threads; the file lock covers processes. Both are
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
    public Map<String, VaultEntry> read(char[] passphrase) {
        return read(opened(Opener.passphrase(passphrase)));
    }

    /**
     * Reads and decrypts the vault with a device's share.
     *
     * @param share The 32 bytes that device keeps in its platform's keystore.
     * @return The entries, in the order they were written.
     * @throws VaultException If the file is unreadable, malformed, or no slot takes that share.
     */
    public Map<String, VaultEntry> read(byte[] share) {
        return read(opened(Opener.share(share)));
    }

    /**
     * Reads and decrypts the vault with whatever opens it.
     *
     * @param opener The passphrase or a device's share.
     * @return The entries, in the order they were written.
     * @throws VaultException If the file is unreadable, malformed, or the opener is not a way in.
     */
    public Map<String, VaultEntry> read(Opener opener) {
        return read(opened(opener));
    }

    /**
     * Returns every credential that can open this vault, without opening it.
     * <p>
     * Readable while locked on purpose: an interface has to show which devices can open a vault
     * before anything opens it. Nothing here is secret.
     *
     * @return The slots, the passphrase first. Empty when there is no vault yet.
     * @throws VaultException If the file exists and cannot be read as a vault.
     */
    public java.util.List<Keyslot> slots() {
        if (!exists()) {
            return java.util.List.of();
        }
        return header().slots().stream().map(VaultHeader.Slot::slot).toList();
    }

    /**
     * Enrolls a device, so that its share opens this vault.
     * <p>
     * <strong>The share arrives once and is discarded.</strong> The node derives a wrapping key
     * from it, stores the master key wrapped under that, and keeps nothing it could derive the
     * wrapping key from again. What is left on this machine is worth nothing without the device,
     * and what the device holds is worth nothing without this file.
     *
     * @param opener What opens the vault now - the passphrase, or another device's share.
     * @param share The device's 32 random bytes. The caller clears them.
     * @param name What to call the device in a list.
     * @param storage How the device says it keeps the share, one of {@link Keyslot#STORAGE}.
     * @return The slot that was made.
     * @throws VaultException If the vault will not open, or the share or storage is not usable.
     */
    public Keyslot enroll(Opener opener, byte[] share, String name, String storage) {
        if (share == null || share.length != KEY_LENGTH) {
            throw new VaultException("A share is " + KEY_LENGTH + " bytes");
        }
        if (!Keyslot.STORAGE.contains(storage)) {
            throw new VaultException("'" + storage + "' is not a kind of storage this knows: "
                    + String.join(", ", Keyslot.STORAGE.stream().sorted().toList()));
        }
        final Opened opened = opened(opener);
        for (final VaultHeader.Slot existing : opened.header().slots()) {
            if (keyFor(existing, Opener.share(share)) != null
                    && takes(existing, Opener.share(share))) {
                // Asked again after an answer was lost, most likely. Saying so beats making a
                // second slot for one device, which would then need revoking twice.
                throw new VaultException("That share is already enrolled as '"
                        + existing.slot().name() + "'");
            }
        }
        final byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        final byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        final byte[] key = wrappingKey(share, salt);
        final Keyslot made;
        final java.util.List<VaultHeader.Slot> slots;
        try {
            made = new Keyslot(java.util.UUID.randomUUID().toString(), name, storage,
                    java.time.Instant.now().toString(), "", false);
            slots = new java.util.ArrayList<>(opened.header().slots());
            slots.add(new VaultHeader.Slot(VaultHeader.SHARE_SLOT, made, salt, nonce,
                    wrap(key, nonce, opened.master()), 0, 0, 0));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        writeContent(read(opened(opener)), slots, opened.master());
        return made;
    }

    /**
     * Removes a device's way in.
     * <p>
     * Deleting one wrapped blob, and nothing else: no re-keying, no other device disturbed, no
     * passphrase rotated. That is what keyslots are for.
     *
     * @param opener What opens the vault now.
     * @param id The slot to remove.
     * @return What can still open the vault afterwards.
     * @throws VaultException If there is no such slot, or it is the last way in.
     */
    public java.util.List<Keyslot> revoke(Opener opener, String id) {
        final Opened opened = opened(opener);
        final java.util.List<VaultHeader.Slot> kept = new java.util.ArrayList<>();
        boolean found = false;
        for (final VaultHeader.Slot slot : opened.header().slots()) {
            if (slot.slot().id().equals(id)) {
                found = true;
            } else {
                kept.add(slot);
            }
        }
        if (!found) {
            throw new VaultException("No keyslot of " + file + " is called '" + id + "'");
        }
        if (kept.isEmpty()) {
            // A vault nothing can open is not a revoked device, it is a lost vault.
            throw new VaultException("That is the last way into " + file
                    + ", and removing it would leave the vault openable by nothing");
        }
        writeContent(read(opened(opener)), kept, opened.master());
        return kept.stream().map(VaultHeader.Slot::slot).toList();
    }

    /**
     * Records that a slot has just opened the vault.
     * <p>
     * A write for a timestamp, which is worth it: "last used" is how an operator spots a device
     * that has not been near this machine in months and should not still have a way in. Without
     * it the field in the contract would be permanently empty, which is worse than not having it.
     *
     * @param opener What opened the vault.
     * @param id The slot that did.
     * @return The slot as it now reads.
     * @throws VaultException If the vault cannot be opened or written.
     */
    public Keyslot used(Opener opener, String id) {
        final Opened opened = opened(opener);
        final String now = java.time.Instant.now().toString();
        final java.util.List<VaultHeader.Slot> slots = new java.util.ArrayList<>();
        Keyslot touched = null;
        for (final VaultHeader.Slot slot : opened.header().slots()) {
            if (slot.slot().id().equals(id)) {
                touched = new Keyslot(slot.slot().id(), slot.slot().name(), slot.slot().storage(),
                        slot.slot().enrolled(), now, slot.slot().recovery());
                slots.add(new VaultHeader.Slot(slot.kind(), touched, slot.salt(), slot.nonce(),
                        slot.wrapped(), slot.iterations(), slot.memoryKib(), slot.parallelism()));
            } else {
                slots.add(slot);
            }
        }
        if (touched == null) {
            throw new VaultException("No keyslot of " + file + " is called '" + id + "'");
        }
        writeContent(read(opened(opener)), slots, opened.master());
        return touched;
    }

    /**
     * Says whether one named slot takes a share.
     * <p>
     * For reporting which device just unlocked the vault, and for nothing else: it answers about
     * one slot, so it cannot be used to walk a vault trying shares.
     *
     * @param id The slot.
     * @param share The share.
     * @return {@code true} when that slot opens with it.
     */
    public boolean takes(String id, byte[] share) {
        if (!exists()) {
            return false;
        }
        for (final VaultHeader.Slot slot : header().slots()) {
            if (slot.slot().id().equals(id)) {
                return takes(slot, Opener.share(share));
            }
        }
        return false;
    }

    /**
     * Says whether a slot takes what is offered, without unwrapping anything the caller keeps.
     *
     * @param slot The slot.
     * @param opener What was offered.
     * @return {@code true} when it opens.
     */
    private boolean takes(VaultHeader.Slot slot, Opener opener) {
        final byte[] key = keyFor(slot, opener);
        if (key == null) {
            return false;
        }
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, slot.nonce()));
            Arrays.fill(cipher.doFinal(slot.wrapped()), (byte) 0);
            return true;
        } catch (GeneralSecurityException ex) {
            return false;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private Map<String, VaultEntry> read(Opened opened) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(opened.master(), "AES"),
                    new GCMParameterSpec(TAG_BITS, opened.header().contentNonce()));
            cipher.updateAAD(opened.header().authenticated());
            final byte[] file = opened.file();
            final byte[] cipherText = Arrays.copyOfRange(file, opened.header().contentAt(),
                    file.length);
            return parse(new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            // The slot's own tag already proved the key, so a failure here is the file having
            // been altered rather than a wrong credential - and the message says so.
            throw new VaultException("Cannot decrypt " + file
                    + " - the credential opened a keyslot but the content does not match this"
                    + " vault's header. The file has been altered.");
        } finally {
            Arrays.fill(opened.master(), (byte) 0);
        }
    }

    /**
     * Tells whether a passphrase decrypts this vault.
     *
     * @param passphrase The passphrase.
     * @return {@code true} when the vault opens, {@code false} when it does not for any reason.
     */
    public boolean accepts(char[] passphrase) {
        try {
            read(passphrase);
            return true;
        } catch (VaultException ex) {
            return false;
        }
    }

    /**
     * Encrypts and writes the vault, replacing any previous content.
     *
     * @param entries What to store.
     * @param passphrase The passphrase.
     * @throws VaultException If the file cannot be written.
     */
    public void write(Map<String, VaultEntry> entries, char[] passphrase) {
        write(entries, passphrase, false);
    }

    /**
     * Returns the salt the existing file was written with, if there is one.
     * <p>
     * Readable without the passphrase: the salt sits in the header in the clear, which is what a
     * salt is for. Empty when there is no file yet, or when its header cannot be read - either way
     * a new one is generated rather than the write being refused.
     *
     * @return The salt, or empty.
     */
    /**
     * Overwrites a buffer's characters, so the plaintext does not sit in it until collection.
     * <p>
     * Best effort, and honestly so: growing a StringBuilder copies its contents, and the old array
     * is left behind untouched. It is pre-sized to make that unlikely rather than impossible.
     *
     * @param text Buffer to clear.
     */
    static void wipe(StringBuilder text) {
        for (int index = 0; index < text.length(); index++) {
            text.setCharAt(index, '\0');
        }
    }

    /**
     * What opening the vault produced: the master key, the header it came from, and the file.
     *
     * @param master The unwrapped master key. The caller clears it.
     * @param header The header it was unwrapped from.
     * @param file The whole file, so the content can be decrypted without reading it twice.
     * @param slot Which keyslot opened it.
     */
    private record Opened(byte[] master, VaultHeader header, byte[] file, VaultHeader.Slot slot) {
    }

    /**
     * What may open a vault.
     * <p>
     * Two things do, and they are not interchangeable: a passphrase is typed by a person and
     * derived with Argon2id, a share is held by a device and derived with HKDF. One type rather
     * than two overloads everywhere, so a method that needs "whatever opened this session" can say
     * so.
     */
    public static final class Opener {

        private final char @Nullable [] passphrase;

        private final byte @Nullable [] share;

        private Opener(char @Nullable [] passphrase, byte @Nullable [] share) {
            this.passphrase = passphrase;
            this.share = share;
        }

        /**
         * A person's passphrase, which is keyslot 0.
         *
         * @param passphrase The passphrase.
         * @return The opener.
         */
        public static Opener passphrase(char[] passphrase) {
            return new Opener(passphrase, null);
        }

        /**
         * A device's share.
         *
         * @param share The 32 bytes it keeps.
         * @return The opener.
         */
        public static Opener share(byte[] share) {
            return new Opener(null, share);
        }
    }

    private VaultHeader header() {
        return VaultHeader.read(bytes(), file);
    }

    private byte[] bytes() {
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new VaultException("Cannot read " + file, ex);
        }
    }

    /**
     * Unwraps the master key with whatever was offered.
     *
     * @param opener The passphrase or a share.
     * @return The master key and where it came from.
     * @throws VaultException If no slot takes it.
     */
    private Opened opened(Opener opener) {
        final byte[] content = bytes();
        final VaultHeader header = VaultHeader.read(content, file);
        for (final VaultHeader.Slot slot : header.slots()) {
            final byte[] key = keyFor(slot, opener);
            if (key == null) {
                continue;
            }
            try {
                final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(TAG_BITS, slot.nonce()));
                return new Opened(cipher.doFinal(slot.wrapped()), header, content, slot);
            } catch (GeneralSecurityException ex) {
                // This slot is not the one. A share is tried against every share slot, because a
                // device that lost its slot id must still be able to open the vault it enrolled
                // with.
                continue;
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        }
        if (opener.share != null) {
            throw new VaultException("No keyslot in " + file + " takes that share."
                    + " The device may have been revoked, or this is another vault.");
        }
        throw new VaultException("Cannot open " + file
                + " - wrong passphrase, or the file has been altered."
                + " If the passphrase should be right, a cached one may be in the way:"
                + " run 'sokar vault unlock --forget' and try again");
    }

    /**
     * Derives the key that unwraps one slot, or {@code null} when this opener is not for it.
     *
     * @param slot The slot.
     * @param opener What was offered.
     * @return The slot key, or {@code null}.
     */
    private byte @Nullable [] keyFor(VaultHeader.Slot slot, Opener opener) {
        if (VaultHeader.PASSPHRASE_SLOT.equals(slot.kind())) {
            if (opener.passphrase == null) {
                return null;
            }
            checkCost(slot.iterations(), slot.memoryKib(), slot.parallelism());
            return deriveKey(opener.passphrase, slot.salt(), slot.iterations(), slot.memoryKib(),
                    slot.parallelism());
        }
        return opener.share == null ? null : wrappingKey(opener.share, slot.salt());
    }

    /**
     * Derives a share's wrapping key.
     * <p>
     * HKDF-SHA256 rather than Argon2id: a share is 32 random bytes from a keystore, not something a
     * person chose, so there is nothing to make expensive to guess. <strong>The node cannot produce
     * this key from anything it stores</strong> - that is the property the whole design rests on,
     * and it is why the share is never written down here.
     *
     * @param share The device's share.
     * @param salt This slot's salt.
     * @return A 32-byte key.
     */
    private static byte[] wrappingKey(byte[] share, byte[] salt) {
        final org.bouncycastle.crypto.generators.HKDFBytesGenerator generator =
                new org.bouncycastle.crypto.generators.HKDFBytesGenerator(
                        new org.bouncycastle.crypto.digests.SHA256Digest());
        generator.init(new org.bouncycastle.crypto.params.HKDFParameters(share, salt,
                "sokar-keyslot".getBytes(StandardCharsets.US_ASCII)));
        final byte[] key = new byte[KEY_LENGTH];
        generator.generateBytes(key, 0, key.length);
        return key;
    }

    /**
     * Writes the vault, keeping or replacing the salt.
     * <p>
     * <strong>The salt belongs to the vault, not to the write.</strong> It exists so that
     * precomputing against one vault's passphrase does not help against another, which needs it to
     * be random once - per vault. Uniqueness of the encryption comes from the nonce, which is
     * fresh every time.
     * <p>
     * It used to be regenerated on every write, which meant a full Argon2id derivation - 64 MiB,
     * three passes - for every credential stored, bought nothing identifiable, and made it
     * impossible to cache anything derived from the passphrase, because the derivation changed
     * under it whenever anything was saved.
     * <p>
     * <strong>A new passphrase does get a new salt.</strong> That is the one case where keeping it
     * would preserve an attacker's precomputation across the change, and it is why this is a
     * parameter rather than always reusing.
     *
     * @param entries What to store.
     * @param passphrase The passphrase to encrypt with.
     * @param freshSalt Whether to generate a new salt rather than keep the file's.
     */
    private void write(Map<String, VaultEntry> entries, char[] passphrase, boolean freshSalt) {
        if (!exists()) {
            final byte[] master = freshMaster();
            writeContent(entries, java.util.List.of(
                    passphraseSlot(passphrase, master, describeRecovery(java.util.List.of()))),
                    master);
            return;
        }
        final Opened opened = opened(Opener.passphrase(passphrase));
        writeContent(entries, opened.header().slots(), opened.master());
    }

    /**
     * Writes the vault under a different passphrase, keeping every device.
     * <p>
     * <strong>The master key does not change</strong>, so a rekey replaces exactly one slot and no
     * enrolled device notices. That is the point of keyslots: the passphrase is a way in, not the
     * key.
     *
     * @param entries What to store.
     * @param opened What opening with the old passphrase produced.
     * @param fresh The passphrase to use from now on.
     */
    private void writeRekeyed(Map<String, VaultEntry> entries, Opened opened, char[] fresh) {
        final java.util.List<VaultHeader.Slot> slots = new java.util.ArrayList<>();
        for (final VaultHeader.Slot slot : opened.header().slots()) {
            if (!VaultHeader.PASSPHRASE_SLOT.equals(slot.kind())) {
                slots.add(slot);
            }
        }
        slots.add(0, passphraseSlot(fresh, opened.master(),
                describeRecovery(opened.header().slots())));
        writeContent(entries, slots, opened.master());
    }

    /**
     * Returns what to record about the passphrase slot, keeping what an existing one said.
     *
     * @param slots The slots read from the file, which may be empty.
     * @return The recovery keyslot.
     */
    private Keyslot describeRecovery(java.util.List<VaultHeader.Slot> slots) {
        for (final VaultHeader.Slot slot : slots) {
            if (VaultHeader.PASSPHRASE_SLOT.equals(slot.kind())) {
                return slot.slot();
            }
        }
        return new Keyslot(Keyslot.PASSPHRASE, "passphrase", "USER_SCOPED",
                java.time.Instant.now().toString(), "", true);
    }

    private byte[] freshMaster() {
        final byte[] master = new byte[KEY_LENGTH];
        random.nextBytes(master);
        return master;
    }

    /**
     * Wraps the master key under a passphrase.
     *
     * @param passphrase The passphrase.
     * @param master The master key.
     * @param description What to record about the slot.
     * @return The slot.
     */
    private VaultHeader.Slot passphraseSlot(char[] passphrase, byte[] master,
            Keyslot description) {
        final byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        final byte[] key = deriveKey(passphrase, salt, ITERATIONS, MEMORY_KIB, PARALLELISM);
        final byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        try {
            return new VaultHeader.Slot(VaultHeader.PASSPHRASE_SLOT, description, salt, nonce,
                    wrap(key, nonce, master), ITERATIONS, MEMORY_KIB, PARALLELISM);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Encrypts the master key under one slot's key.
     *
     * @param key The slot key.
     * @param nonce This slot's nonce, used for nothing else.
     * @param master The master key.
     * @return The wrapped key, with its tag.
     */
    private byte[] wrap(byte[] key, byte[] nonce, byte[] master) {
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(master);
        } catch (GeneralSecurityException ex) {
            throw new VaultException("Cannot wrap the vault's master key", ex);
        }
    }

    private void writeContent(Map<String, VaultEntry> entries,
            java.util.List<VaultHeader.Slot> slots, byte[] master) {

        final byte[] contentNonce = new byte[NONCE_LENGTH];
        random.nextBytes(contentNonce);
        final VaultHeader header = VaultHeader.of(slots, contentNonce);

        // Not Json.write(...).getBytes(...): that hands back a String holding every credential in
        // this vault in plaintext, which cannot be cleared and lives until the collector gets to
        // it. Written into a buffer this owns instead, and overwritten once it is encrypted.
        //
        // This reduces copies rather than erasing anything. Values are Strings elsewhere in this
        // module, and a moving collector copies objects, so no wipe in a managed runtime is a
        // guarantee - see the requirement for what a full pass would cost.
        final StringBuilder json = new StringBuilder(1024);
        Json.write(document(entries), json);
        final java.nio.ByteBuffer encoded =
                StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(json));
        final byte[] plaintext = new byte[encoded.remaining()];
        encoded.get(plaintext);
        wipe(json);

        final byte[] cipherText;
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"),
                    new GCMParameterSpec(TAG_BITS, contentNonce));
            cipher.updateAAD(header.authenticated());
            cipherText = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException ex) {
            throw new VaultException("Cannot encrypt the vault", ex);
        } finally {
            Arrays.fill(master, (byte) 0);
            // No test covers this line and none can from outside: the buffer is local and gone by
            // the time anything could look at it. Kept because it is the same discipline as the
            // key beside it, and noted so it is not deleted as dead on the grounds that nothing
            // failed when it was.
            Arrays.fill(plaintext, (byte) 0);
        }

        atomicWrite(header.authenticated(), cipherText);
    }

    /**
     * Refuses KDF parameters that no Sokar version would have written.
     * <p>
     * <strong>These are read from the file and used before the authentication tag can be
     * checked</strong> - deriving the key is what produces the ability to check it, so there is no
     * ordering that avoids this. Anyone who can write the file can therefore choose the cost, and
     * a single flipped byte turns three iterations into sixteen million: the process hangs, or
     * allocates until it is killed. Bounding the values is the only defense, and it has to happen
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
            // The rename itself has to reach the disk too. force() above makes the CONTENT
            // durable; on a filesystem that needs the directory synced, a crash between the move
            // and the next flush can leave the old name pointing at nothing - which for a vault
            // is the file that holds every credential. Forcing the directory is what makes
            // "atomic replacement" true rather than usually true.
            try (FileChannel parent = FileChannel.open(directory,
                    java.nio.file.StandardOpenOption.READ)) {
                parent.force(true);
            }
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
    /**
     * Re-encrypts the vault under a different passphrase.
     * <p>
     * Under the same advisory lock {@link #update} holds, and for the same reason: another
     * {@code sokar} storing a credential halfway through would either be lost or be written under
     * the old passphrase. Read with the old, written with the new, in one cycle - and
     * {@link #write} replaces the file atomically, so an interruption leaves the vault readable
     * with one passphrase or the other and never with neither.
     * <p>
     * <strong>What this does not touch.</strong> A running task's credential proxy read what it
     * needed when the task started and holds it in its own memory, so nothing running is locked
     * out. Anything holding the OLD passphrase in the kernel keyring now holds the wrong one -
     * dropping it is the caller's job, and not doing so turns the next command into a confusing
     * failure.
     *
     * @param passphrase The current passphrase.
     * @param fresh What to encrypt under from now on.
     * @throws VaultException If the vault cannot be locked, read with the old, or written.
     */
    public void rekey(char[] passphrase, char[] fresh) {
        update(passphrase, entries -> entries, fresh, true);
    }

    public void update(char[] passphrase,
            java.util.function.UnaryOperator<Map<String, VaultEntry>> change) {
        update(passphrase, change, passphrase, false);
    }

    /**
     * Changes what the vault holds, opening it with whatever opens it.
     * <p>
     * For everything that needs the vault open rather than needing the passphrase in particular: a
     * machine unlocked by a device can store a credential exactly as one unlocked by a person can,
     * and neither has to know which the other used.
     *
     * @param opener The passphrase or a held share.
     * @param change What to do with the entries.
     * @throws VaultException If the vault cannot be locked, opened or written.
     */
    public void update(Opener opener,
            java.util.function.UnaryOperator<Map<String, VaultEntry>> change) {

        final Path absolute = file.toAbsolutePath();
        final Path lockFile = absolute.resolveSibling(absolute.getFileName() + ".lock");
        final java.util.concurrent.locks.ReentrantLock threadLock = THREAD_LOCKS.computeIfAbsent(
                absolute, key -> new java.util.concurrent.locks.ReentrantLock());

        threadLock.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(lockFile.toFile(), "rw");
                    FileLock lock = raf.getChannel().lock()) {
                if (!exists()) {
                    throw new VaultException("There is no vault at " + file
                            + " to change. Create it first.");
                }
                final Opened opened = opened(opener);
                final Map<String, VaultEntry> current =
                        new LinkedHashMap<>(read(opened(opener)));
                writeContent(change.apply(current), opened.header().slots(), opened.master());
            }
        } catch (IOException ex) {
            throw new VaultException("Cannot lock " + lockFile, ex);
        } finally {
            threadLock.unlock();
        }
    }

    private void update(char[] passphrase,
            java.util.function.UnaryOperator<Map<String, VaultEntry>> change, char[] writeWith,
            boolean rekeying) {

        final Path absolute = file.toAbsolutePath();
        final Path lockFile = absolute.resolveSibling(absolute.getFileName() + ".lock");

        final java.util.concurrent.locks.ReentrantLock threadLock = THREAD_LOCKS.computeIfAbsent(
                absolute, key -> new java.util.concurrent.locks.ReentrantLock());

        threadLock.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(lockFile.toFile(), "rw");
                    FileLock lock = raf.getChannel().lock()) {
                if (!exists()) {
                    write(change.apply(new LinkedHashMap<>()), writeWith, false);
                    return;
                }
                // Opened once, with the passphrase that is current now. A rekey then writes under
                // the new one without opening again - opening with the new passphrase is exactly
                // the mistake that would make a rekey impossible.
                final Opened opened = opened(Opener.passphrase(passphrase));
                final Map<String, VaultEntry> current =
                        new LinkedHashMap<>(read(opened(Opener.passphrase(passphrase))));
                final Map<String, VaultEntry> next = change.apply(current);
                if (rekeying) {
                    writeRekeyed(next, opened, writeWith);
                } else {
                    writeContent(next, opened.header().slots(), opened.master());
                }
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

    /**
     * Reads the stored document.
     * <p>
     * A value is either the secret itself, which is how vaults were written before credentials
     * carried a kind, or an object holding the secret and its kind. Both are accepted so an
     * existing vault keeps working.
     *
     * @param json Decrypted document.
     * @return Entries in the order they were written.
     */
    private static Map<String, VaultEntry> parse(String json) {
        if (!(Json.parse(json) instanceof Map<?, ?> map)) {
            throw new VaultException("The vault does not contain a JSON object");
        }
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        map.forEach((key, value) -> entries.put(String.valueOf(key),
                value instanceof Map<?, ?> object
                        ? new VaultEntry(String.valueOf(object.get("value")),
                                object.get("type") == null ? null : String.valueOf(object.get("type")),
                                settings(object.get("settings")))
                        : VaultEntry.of(String.valueOf(value))));
        return entries;
    }

    /**
     * Renders the entries for storage, keeping the short form where no kind is known.
     *
     * @param entries What to store.
     * @return A document of plain values and objects.
     */
    private static Map<String, Object> document(Map<String, VaultEntry> entries) {
        final Map<String, Object> document = new LinkedHashMap<>();
        entries.forEach((name, entry) -> {
            if (entry.type() == null && entry.settings().isEmpty()) {
                document.put(name, entry.value());
                return;
            }
            final Map<String, Object> object = new LinkedHashMap<>();
            object.put("value", entry.value());
            if (entry.type() != null) {
                object.put("type", entry.type());
            }
            if (!entry.settings().isEmpty()) {
                object.put("settings", new LinkedHashMap<>(entry.settings()));
            }
            document.put(name, object);
        });
        return document;
    }

    private static Map<String, String> settings(@Nullable Object value) {
        final Map<String, String> settings = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> named) {
            named.forEach((key, setting) -> settings.put(String.valueOf(key), String.valueOf(setting)));
        }
        return settings;
    }
}
