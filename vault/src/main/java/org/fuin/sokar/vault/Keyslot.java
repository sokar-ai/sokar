package org.fuin.sokar.vault;

/**
 * One credential allowed to open the vault, and what is known about it without opening it.
 * <p>
 * Adapted from what LUKS does: the master key is stored only in wrapped form, once per keyslot, and
 * no credential is the master key. Adding a device adds a slot; removing one deletes a slot. Nothing
 * else changes - no re-keying, no other device disturbed, no passphrase rotated.
 * <p>
 * <strong>Everything here is readable while the vault is locked</strong>, because it is written
 * beside the wrapped key rather than inside the encrypted document. That is deliberate: an interface
 * has to be able to show which devices can open a vault <em>before</em> anything opens it. Nothing
 * in it is secret - a device's name, when it was enrolled, how that platform stores its share - and
 * the whole header is authenticated, so none of it can be changed without invalidating the content.
 *
 * @param id Assigned by the node when the slot is made, and kept by the device beside its share.
 *        Not derived from the share: the node discards that at enrollment and could derive nothing
 *        from it afterwards.
 * @param name What a person called this device. Shown in a list; never an identifier.
 * @param storage How the device says it keeps its share, as one of {@link #STORAGE}. Recorded, not
 *        verified - the node cannot check what another machine does with its own keystore, and
 *        saying so is more honest than implying a guarantee.
 * @param enrolled When the slot was made, RFC 3339.
 * @param lastUsed When it last opened the vault, or "" when it has not since it was enrolled.
 * @param recovery True for keyslot 0, the passphrase: typed by a person in an emergency, stored
 *        nowhere, and not what day-to-day unlocking uses.
 */
public record Keyslot(String id, String name, String storage, String enrolled, String lastUsed,
        boolean recovery) {

    /** The id of keyslot 0, which is the passphrase and is always present. */
    public static final String PASSPHRASE = "passphrase";

    /** How a device may say it stores its share. */
    public static final java.util.Set<String> STORAGE = java.util.Set.of(
            // Any process running as that user can ask for it: a Secret Service keyring that
            // unlocks at login, or Windows DPAPI. The common case on a desktop, and worth less
            // than it sounds.
            "USER_SCOPED",
            // Only this application can ask: iOS, Android, macOS with code signing.
            "APPLICATION_SCOPED",
            // Not stored at all: derived at unlock time from a token's hmac-secret, so releasing
            // it needs a physical touch that same-user code cannot supply.
            "FIDO2",
            // Not stored at all: sealed behind a PIN in a TPM2 object.
            "TPM2");
}
