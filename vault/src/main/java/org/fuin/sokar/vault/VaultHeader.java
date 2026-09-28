package org.fuin.sokar.vault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The part of a vault file that is readable without opening it.
 * <p>
 * <pre>
 * magic "SOKARVLT" | version | header length | header JSON | content nonce | AEAD ciphertext+tag
 * </pre>
 * <p>
 * <strong>JSON rather than packed binary</strong>, because what it holds is a table of variable
 * length whose shape will grow - a slot has a name, a storage kind and two timestamps - and a
 * hand-rolled parser for that is a place for a length field to be read wrong. It costs a few
 * hundred bytes in a file that holds tens of entries.
 * <p>
 * <strong>Everything from the magic to the content nonce is authenticated as additional data</strong>,
 * so no part of this can be changed without invalidating the content's tag: not a slot's wrapped
 * key, not the KDF parameters, not a device's recorded name.
 */
final class VaultHeader {

    /** Magic bytes at the start of every vault file. */
    static final byte[] MAGIC = "SOKARVLT".getBytes(StandardCharsets.US_ASCII);

    /** The only format there is. Version 1 was passphrase-only and is refused, never migrated. */
    static final int VERSION = 2;

    /** A slot the passphrase opens: keyslot 0, and the recovery credential. */
    static final String PASSPHRASE_SLOT = "passphrase";

    /** A slot a device's share opens. */
    static final String SHARE_SLOT = "share";

    /**
     * One slot's stored form: what it is, who it belongs to, and the master key wrapped for it.
     *
     * @param kind {@link #PASSPHRASE_SLOT} or {@link #SHARE_SLOT}.
     * @param slot What is shown about it while the vault is locked.
     * @param salt Argon2id salt for a passphrase slot, HKDF salt for a share slot.
     * @param nonce The nonce the master key was wrapped under, for this slot alone.
     * @param wrapped The master key encrypted under this slot's key, with its tag.
     * @param iterations Argon2id iterations, for a passphrase slot. Zero otherwise.
     * @param memoryKib Argon2id memory, for a passphrase slot. Zero otherwise.
     * @param parallelism Argon2id parallelism, for a passphrase slot. Zero otherwise.
     */
    record Slot(String kind, Keyslot slot, byte[] salt, byte[] nonce, byte[] wrapped,
            int iterations, int memoryKib, int parallelism) {
    }

    private final List<Slot> slots;

    private final byte[] contentNonce;

    private final byte[] authenticated;

    private VaultHeader(final List<Slot> slots, final byte[] contentNonce,
            final byte[] authenticated) {
        this.slots = slots;
        this.contentNonce = contentNonce;
        this.authenticated = authenticated;
    }

    List<Slot> slots() {
        return slots;
    }

    byte[] contentNonce() {
        return contentNonce.clone();
    }

    /**
     * Returns the bytes covered by the content's authentication tag.
     *
     * @return Magic, version, length, JSON and the content nonce.
     */
    byte[] authenticated() {
        return authenticated;
    }

    /**
     * Returns where the encrypted content starts.
     *
     * @return Offset into the file.
     */
    int contentAt() {
        return authenticated.length;
    }

    /**
     * Builds a header.
     *
     * @param slots The slots, passphrase first.
     * @param contentNonce The nonce the content is encrypted under.
     * @return The header, with its bytes ready to write.
     */
    static VaultHeader of(final List<Slot> slots, final byte[] contentNonce) {
        final List<Object> written = new ArrayList<>();
        for (final Slot slot : slots) {
            final Map<String, Object> one = new LinkedHashMap<>();
            one.put("kind", slot.kind());
            one.put("id", slot.slot().id());
            one.put("name", slot.slot().name());
            one.put("storage", slot.slot().storage());
            one.put("enrolled", slot.slot().enrolled());
            one.put("lastUsed", slot.slot().lastUsed());
            one.put("salt", encode(slot.salt()));
            one.put("nonce", encode(slot.nonce()));
            one.put("wrapped", encode(slot.wrapped()));
            if (PASSPHRASE_SLOT.equals(slot.kind())) {
                one.put("iterations", slot.iterations());
                one.put("memory", slot.memoryKib());
                one.put("parallelism", slot.parallelism());
            }
            written.add(one);
        }
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("slots", written);
        final byte[] json = Json.write(document).getBytes(StandardCharsets.UTF_8);

        final ByteBuffer buffer = ByteBuffer.allocate(
                MAGIC.length + 4 + 4 + json.length + contentNonce.length);
        buffer.put(MAGIC);
        buffer.putInt(VERSION);
        buffer.putInt(json.length);
        buffer.put(json);
        buffer.put(contentNonce);
        return new VaultHeader(List.copyOf(slots), contentNonce.clone(), buffer.array());
    }

    /**
     * Reads a header off a file's bytes.
     *
     * @param content The whole file.
     * @param name What to call the file in an error.
     * @return The header.
     * @throws VaultException If this is not a vault, or not this version, or malformed.
     */
    static VaultHeader read(final byte[] content, final Object name) {
        if (content.length < MAGIC.length + 8) {
            throw new VaultException(name + " is too short to be a vault");
        }
        final ByteBuffer buffer = ByteBuffer.wrap(content);
        final byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new VaultException(name + " is not a Sokar vault");
        }
        final int version = buffer.getInt();
        if (version != VERSION) {
            // Refused, never half read. There is one format, and a file from before it is not
            // migrated - it is recreated, which costs only retyping what it held.
            throw new VaultException(name + " is a version " + version + " vault, and this Sokar"
                    + " reads version " + VERSION + " only. There is no conversion: create the"
                    + " vault again with 'sokar vault init' and enter its credentials once more.");
        }
        final int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            throw new VaultException(name + " has a malformed header");
        }
        final byte[] json = new byte[length];
        buffer.get(json);
        final byte[] contentNonce = new byte[VaultFile.NONCE_LENGTH];
        if (buffer.remaining() < contentNonce.length) {
            throw new VaultException(name + " has a malformed header");
        }
        buffer.get(contentNonce);

        final List<Slot> slots = new ArrayList<>();
        final Object parsed;
        try {
            parsed = Json.parse(new String(json, StandardCharsets.UTF_8));
        } catch (final RuntimeException ex) {
            // A reader of this file catches VaultException and nothing else. Letting the JSON
            // parser's own exception out would reach a caller as an unhandled failure, when what
            // happened is exactly what this class exists to report: the file is not readable.
            throw new VaultException(name + " has a malformed header", ex);
        }
        if (!(parsed instanceof Map<?, ?> document)
                || !(document.get("slots") instanceof List<?> written)) {
            throw new VaultException(name + " has no keyslots");
        }
        for (final Object each : written) {
            if (each instanceof Map<?, ?> one) {
                slots.add(slot(one, name));
            }
        }
        if (slots.isEmpty()) {
            throw new VaultException(name + " has no keyslots");
        }
        final byte[] authenticated =
                java.util.Arrays.copyOfRange(content, 0, buffer.position());
        return new VaultHeader(List.copyOf(slots), contentNonce, authenticated);
    }

    private static Slot slot(final Map<?, ?> one, final Object name) {
        final String kind = text(one, "kind");
        final Keyslot keyslot = new Keyslot(text(one, "id"), text(one, "name"),
                text(one, "storage"), text(one, "enrolled"), text(one, "lastUsed"),
                PASSPHRASE_SLOT.equals(kind));
        final byte[] salt = decode(one.get("salt"), name);
        final byte[] nonce = decode(one.get("nonce"), name);
        final byte[] wrapped = decode(one.get("wrapped"), name);
        return new Slot(kind, keyslot, salt, nonce, wrapped, number(one, "iterations"),
                number(one, "memory"), number(one, "parallelism"));
    }

    private static String text(final Map<?, ?> one, final String key) {
        return one.get(key) instanceof String value ? value : "";
    }

    private static int number(final Map<?, ?> one, final String key) {
        return one.get(key) instanceof Number value ? value.intValue() : 0;
    }

    private static String encode(final byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(final @Nullable Object value, final Object name) {
        if (!(value instanceof String text)) {
            throw new VaultException(name + " has a malformed keyslot");
        }
        try {
            return Base64.getDecoder().decode(text);
        } catch (final IllegalArgumentException ex) {
            throw new VaultException(name + " has a malformed keyslot", ex);
        }
    }
}
