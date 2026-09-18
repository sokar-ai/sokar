package org.fuin.sokar.app;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.fuin.sokar.vault.Keyslot;
import org.fuin.sokar.vault.VaultException;
import org.fuin.sokar.vault.VaultFile;

/**
 * What an interface may do with the vault's keyslots, and what each answer means.
 * <p>
 * One place for the four operations B60 describes, so the daemon's methods and any command line
 * render the same outcomes rather than each deciding what counts as a failure.
 * <p>
 * <strong>A share never appears in an outcome, a log line or a message.</strong> It arrives, it is
 * used, and it is gone; what is reported back is which slot it made or opened.
 */
public final class Keyslots {

    /** What a share is on the wire: 32 bytes, base64. */
    public static final int SHARE_BYTES = 32;

    /** What an enrollment did. */
    public enum Enrolled {

        /** The device can now open the vault. */
        ENROLLED,

        /** That share already has a slot. A retry after a lost answer, most likely. */
        ALREADY_ENROLLED,

        /** The device named a kind of storage this does not know. */
        UNKNOWN_STORAGE,

        /** The share is not 32 bytes of base64. */
        BAD_SHARE,

        /** Nothing here can open the vault, so nothing can wrap the master key for a device. */
        VAULT_LOCKED,

        /** There is no vault on this machine yet. */
        VAULT_WITHOUT_KEYSLOTS,

        /** Something else, said in the detail. */
        FAILED
    }

    /** What a revocation did. */
    public enum Revoked {

        /** The device can no longer open the vault. */
        REVOKED,

        /** No slot has that id. */
        NO_SUCH_SLOT,

        /** Removing it would leave the vault openable by nothing. */
        LAST_WAY_IN,

        /** Nothing here can open the vault. */
        VAULT_LOCKED,

        /** There is no vault on this machine yet. */
        VAULT_WITHOUT_KEYSLOTS,

        /** Something else, said in the detail. */
        FAILED
    }

    /** What an unlock did. */
    public enum Unlocked {

        /** The vault is open, until the moment reported. */
        UNLOCKED,

        /** No keyslot takes that share. */
        SHARE_REJECTED,

        /** The share is not 32 bytes of base64. */
        BAD_SHARE,

        /** There is no vault on this machine yet. */
        VAULT_WITHOUT_KEYSLOTS,

        /** Something else, said in the detail. */
        FAILED
    }

    /**
     * What enrolling answered.
     *
     * @param outcome What happened.
     * @param slot The slot that was made, or {@code null}.
     * @param detail Why, or "".
     */
    public record Enrollment(Enrolled outcome, @org.jspecify.annotations.Nullable Keyslot slot,
            String detail) {
    }

    /**
     * What revoking answered.
     *
     * @param outcome What happened.
     * @param remaining What can still open the vault, so a screen can say it without asking again.
     * @param detail Why, or "".
     */
    public record Revocation(Revoked outcome, List<Keyslot> remaining, String detail) {
    }

    /**
     * What unlocking answered.
     *
     * @param outcome What happened.
     * @param until When the unlock runs out, RFC 3339, or "".
     * @param slot Which slot opened it, or {@code null}.
     * @param detail Why, or "".
     */
    public record Unlock(Unlocked outcome, String until,
            @org.jspecify.annotations.Nullable Keyslot slot, String detail) {
    }

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    public Keyslots(final SokarContext context) {
        this.context = context;
    }

    /**
     * Lists what can open this vault.
     *
     * @return The slots, the passphrase first. Empty when there is no vault.
     */
    public List<Keyslot> list() {
        try {
            return context.vault().slots();
        } catch (final VaultException ex) {
            // A vault nobody can read is not a list of slots. Empty rather than an error: this is
            // what a screen asks first, and it has 'doctor' to tell it why.
            return List.of();
        }
    }

    /**
     * Enrolls a device.
     *
     * @param name What to call it.
     * @param share Its share, base64.
     * @param storage How it says it keeps the share.
     * @return What happened.
     */
    public Enrollment enroll(final String name, final String share, final String storage) {
        if (!context.vault().exists()) {
            return new Enrollment(Enrolled.VAULT_WITHOUT_KEYSLOTS, null,
                    "there is no vault on this machine yet");
        }
        final byte[] bytes = decode(share);
        if (bytes == null) {
            return new Enrollment(Enrolled.BAD_SHARE, null,
                    "a share is " + SHARE_BYTES + " bytes, base64");
        }
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty()) {
            return new Enrollment(Enrolled.VAULT_LOCKED, null,
                    "nothing here can open the vault, so nothing can wrap its key for a device");
        }
        try {
            return new Enrollment(Enrolled.ENROLLED,
                    context.vault().enroll(opener.get(), bytes, name, storage), "");
        } catch (final VaultException ex) {
            final String said = String.valueOf(ex.getMessage());
            if (said.contains("already enrolled")) {
                return new Enrollment(Enrolled.ALREADY_ENROLLED, null, said);
            }
            if (said.contains("not a kind of storage")) {
                return new Enrollment(Enrolled.UNKNOWN_STORAGE, null, said);
            }
            return new Enrollment(Enrolled.FAILED, null, said);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Removes a device's way in.
     *
     * @param id The slot.
     * @return What happened, and what is left.
     */
    public Revocation revoke(final String id) {
        if (!context.vault().exists()) {
            return new Revocation(Revoked.VAULT_WITHOUT_KEYSLOTS, List.of(),
                    "there is no vault on this machine yet");
        }
        final Optional<VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty()) {
            return new Revocation(Revoked.VAULT_LOCKED, List.of(),
                    "nothing here can open the vault");
        }
        try {
            return new Revocation(Revoked.REVOKED, context.vault().revoke(opener.get(), id), "");
        } catch (final VaultException ex) {
            final String said = String.valueOf(ex.getMessage());
            if (said.contains("last way into")) {
                return new Revocation(Revoked.LAST_WAY_IN, list(), said);
            }
            if (said.contains("No keyslot")) {
                return new Revocation(Revoked.NO_SUCH_SLOT, list(), said);
            }
            return new Revocation(Revoked.FAILED, List.of(), said);
        }
    }

    /**
     * Opens the vault with a device's share, for a while.
     *
     * @param share The share, base64.
     * @param minutes How long, or {@code null} for the default.
     * @return What happened.
     */
    public Unlock unlock(final String share, final @org.jspecify.annotations.Nullable Integer minutes) {
        if (!context.vault().exists()) {
            return new Unlock(Unlocked.VAULT_WITHOUT_KEYSLOTS, "", null,
                    "there is no vault on this machine yet");
        }
        final byte[] bytes = decode(share);
        if (bytes == null) {
            return new Unlock(Unlocked.BAD_SHARE, "", null,
                    "a share is " + SHARE_BYTES + " bytes, base64");
        }
        try {
            // Opened first, so a share that opens nothing is refused before anything is held: an
            // unlock that cached a useless share would report success and change nothing.
            context.vault().read(bytes);
            final Duration howLong = minutes == null || minutes <= 0
                    ? VaultShare.DEFAULT : Duration.ofMinutes(minutes);
            VaultShare.keep(context.paths(), bytes, howLong);
            return new Unlock(Unlocked.UNLOCKED, Instant.now().plus(howLong).toString(),
                    slotFor(bytes), "");
        } catch (final VaultException ex) {
            return new Unlock(Unlocked.SHARE_REJECTED, "", null, String.valueOf(ex.getMessage()));
        } catch (final RuntimeException ex) {
            return new Unlock(Unlocked.FAILED, "", null, String.valueOf(ex.getMessage()));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Returns which slot a share opens, for reporting.
     *
     * @param share The share.
     * @return The slot, or {@code null} when it cannot be told.
     */
    private @org.jspecify.annotations.Nullable Keyslot slotFor(final byte[] share) {
        for (final Keyslot slot : list()) {
            if (!slot.recovery() && context.vault().takes(slot.id(), share)) {
                return slot;
            }
        }
        return null;
    }

    private static byte @org.jspecify.annotations.Nullable [] decode(final String share) {
        try {
            final byte[] bytes = Base64.getDecoder().decode(share);
            return bytes.length == SHARE_BYTES ? bytes : null;
        } catch (final IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }
}
